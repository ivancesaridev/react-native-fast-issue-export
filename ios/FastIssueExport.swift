import Foundation
import UIKit
import ReplayKit
import AVFoundation

@objc(FastIssueExport)
class FastIssueExport: RCTEventEmitter {

  private let recorder = RPScreenRecorder.shared()
  private var isBuffering = false

  // MARK: - RCTEventEmitter

  override static func requiresMainQueueSetup() -> Bool {
    return false
  }

  override func supportedEvents() -> [String] {
    return ["onShakeDetected", "onBugReportReady", "onError"]
  }

  // MARK: - Buffering

  @objc
  func startBuffering(_ resolve: @escaping RCTPromiseResolveBlock,
                      reject: @escaping RCTPromiseRejectBlock) {
    guard recorder.isAvailable else {
      reject("ERR_UNAVAILABLE", "Screen recording is not available on this device.", nil)
      return
    }

    if #available(iOS 15.0, *) {
      recorder.startClipBuffering { [weak self] error in
        if let error = error {
          reject("ERR_START_BUFFERING", error.localizedDescription, error)
        } else {
          self?.isBuffering = true
          resolve(nil)
        }
      }
    } else {
      reject("ERR_UNSUPPORTED", "Clip buffering requires iOS 15.0 or later.", nil)
    }
  }

  @objc
  func stopBuffering(_ resolve: @escaping RCTPromiseResolveBlock,
                     reject: @escaping RCTPromiseRejectBlock) {
    guard isBuffering else {
      resolve(nil)
      return
    }

    if #available(iOS 15.0, *) {
      recorder.stopClipBuffering { [weak self] error in
        if let error = error {
          reject("ERR_STOP_BUFFERING", error.localizedDescription, error)
        } else {
          self?.isBuffering = false
          resolve(nil)
        }
      }
    } else {
      resolve(nil)
    }
  }

  @objc
  func saveClip(_ resolve: @escaping RCTPromiseResolveBlock,
                reject: @escaping RCTPromiseRejectBlock) {
    guard isBuffering else {
      reject("ERR_NOT_BUFFERING", "Buffering is not active. Call startBuffering() first.", nil)
      return
    }

    if #available(iOS 15.0, *) {
      let tempDir = NSTemporaryDirectory()
      let clipA = URL(fileURLWithPath: tempDir).appendingPathComponent("clip_a_\(UUID().uuidString).mp4")
      let clipB = URL(fileURLWithPath: tempDir).appendingPathComponent("clip_b_\(UUID().uuidString).mp4")

      // Export first 15-second clip
      recorder.exportClip(to: clipA, duration: 15.0) { [weak self] error in
        if let error = error {
          reject("ERR_EXPORT_CLIP_A", error.localizedDescription, error)
          return
        }

        // Wait briefly for the buffer to advance, then export a second overlapping clip
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
          self?.recorder.exportClip(to: clipB, duration: 15.0) { error in
            if let error = error {
              // If second export fails, return just the first clip
              resolve(clipA.path)
              return
            }

            // Concatenate both clips into a single video
            self?.concatenateVideos(clipA: clipA, clipB: clipB) { result in
              // Clean up temp clips
              try? FileManager.default.removeItem(at: clipA)
              try? FileManager.default.removeItem(at: clipB)

              switch result {
              case .success(let outputPath):
                resolve(outputPath)
              case .failure(let error):
                reject("ERR_CONCAT", error.localizedDescription, error)
              }
            }
          }
        }
      }
    } else {
      reject("ERR_UNSUPPORTED", "Clip buffering requires iOS 15.0 or later.", nil)
    }
  }

  // MARK: - Video Concatenation

  private func concatenateVideos(clipA: URL, clipB: URL,
                                  completion: @escaping (Result<String, Error>) -> Void) {
    let composition = AVMutableComposition()

    guard
      let videoTrack = composition.addMutableTrack(
        withMediaType: .video,
        preferredTrackID: kCMPersistentTrackID_Invalid
      )
    else {
      completion(.failure(NSError(domain: "FastIssueExport", code: -1,
                                   userInfo: [NSLocalizedDescriptionKey: "Could not create video track"])))
      return
    }

    // Optional audio track
    let audioTrack = composition.addMutableTrack(
      withMediaType: .audio,
      preferredTrackID: kCMPersistentTrackID_Invalid
    )

    do {
      let assetA = AVURLAsset(url: clipA)
      let assetB = AVURLAsset(url: clipB)

      // Insert clip A
      if let videoA = assetA.tracks(withMediaType: .video).first {
        try videoTrack.insertTimeRange(
          CMTimeRange(start: .zero, duration: assetA.duration),
          of: videoA,
          at: .zero
        )
        videoTrack.preferredTransform = videoA.preferredTransform
      }

      if let audioA = assetA.tracks(withMediaType: .audio).first {
        try audioTrack?.insertTimeRange(
          CMTimeRange(start: .zero, duration: assetA.duration),
          of: audioA,
          at: .zero
        )
      }

      // Insert clip B after A
      let insertTime = assetA.duration
      if let videoB = assetB.tracks(withMediaType: .video).first {
        try videoTrack.insertTimeRange(
          CMTimeRange(start: .zero, duration: assetB.duration),
          of: videoB,
          at: insertTime
        )
      }

      if let audioB = assetB.tracks(withMediaType: .audio).first {
        try audioTrack?.insertTimeRange(
          CMTimeRange(start: .zero, duration: assetB.duration),
          of: audioB,
          at: insertTime
        )
      }
    } catch {
      completion(.failure(error))
      return
    }

    // Export combined composition
    let outputURL = URL(fileURLWithPath: NSTemporaryDirectory())
      .appendingPathComponent("bug_report_\(UUID().uuidString).mp4")

    guard let exportSession = AVAssetExportSession(asset: composition,
                                                    presetName: AVAssetExportPresetPassthrough) else {
      completion(.failure(NSError(domain: "FastIssueExport", code: -2,
                                   userInfo: [NSLocalizedDescriptionKey: "Could not create export session"])))
      return
    }

    exportSession.outputURL = outputURL
    exportSession.outputFileType = .mp4

    exportSession.exportAsynchronously {
      switch exportSession.status {
      case .completed:
        completion(.success(outputURL.path))
      case .failed:
        completion(.failure(exportSession.error ?? NSError(domain: "FastIssueExport", code: -3,
                            userInfo: [NSLocalizedDescriptionKey: "Export failed"])))
      default:
        completion(.failure(NSError(domain: "FastIssueExport", code: -4,
                            userInfo: [NSLocalizedDescriptionKey: "Export ended with status: \(exportSession.status.rawValue)"])))
      }
    }
  }

  // MARK: - Device Info

  @objc
  func getDeviceInfo(_ resolve: @escaping RCTPromiseResolveBlock,
                     reject: @escaping RCTPromiseRejectBlock) {
    var systemInfo = utsname()
    uname(&systemInfo)
    let modelCode = withUnsafePointer(to: &systemInfo.machine) {
      $0.withMemoryRebound(to: CChar.self, capacity: 1) {
        String(validatingUTF8: $0) ?? "Unknown"
      }
    }

    let device = UIDevice.current
    let appVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "Unknown"
    let buildNumber = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "Unknown"

    let info: [String: Any] = [
      "platform": "ios",
      "deviceModel": modelCode,
      "deviceName": device.name,
      "systemName": device.systemName,
      "systemVersion": device.systemVersion,
      "appVersion": appVersion,
      "appBuild": buildNumber,
      "timestamp": ISO8601DateFormatter().string(from: Date())
    ]

    resolve(info)
  }

  // MARK: - Shake Detection (via UIWindow subclass)

  @objc
  func enableShakeDetection() {
    DispatchQueue.main.async {
      ShakeDetector.shared.onShake = { [weak self] in
        self?.sendEvent(withName: "onShakeDetected", body: nil)
      }
      ShakeDetector.shared.startListening()
    }
  }

  @objc
  func disableShakeDetection() {
    DispatchQueue.main.async {
      ShakeDetector.shared.stopListening()
    }
  }
}

// MARK: - ShakeDetector

/// Uses UIApplication's motionBegan to detect shake.
/// We swizzle UIWindow to intercept the motion event.
class ShakeDetector: NSObject {

  static let shared = ShakeDetector()

  var onShake: (() -> Void)?
  private var isListening = false

  func startListening() {
    guard !isListening else { return }
    isListening = true
    ShakeDetectingWindow.onShake = { [weak self] in
      self?.onShake?()
    }
    ShakeDetectingWindow.swizzleIfNeeded()
  }

  func stopListening() {
    isListening = false
    ShakeDetectingWindow.onShake = nil
  }
}

/// Swizzles `motionEnded` on UIWindow to detect shake gesture.
class ShakeDetectingWindow: NSObject {

  static var onShake: (() -> Void)?
  private static var hasSwizzled = false

  static func swizzleIfNeeded() {
    guard !hasSwizzled else { return }
    hasSwizzled = true

    let original = class_getInstanceMethod(UIWindow.self, #selector(UIWindow.motionEnded(_:with:)))
    let swizzled = class_getInstanceMethod(ShakeDetectingWindow.self, #selector(handleMotionEnded(_:with:)))

    if let original = original, let swizzled = swizzled {
      method_exchangeImplementations(original, swizzled)
    }
  }

  @objc func handleMotionEnded(_ motion: UIEvent.EventSubtype, with event: UIEvent?) {
    // Call original implementation (after swizzle, this calls original UIWindow.motionEnded)
    handleMotionEnded(motion, with: event)

    if motion == .motionShake {
      ShakeDetectingWindow.onShake?()
    }
  }
}
