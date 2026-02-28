# fast-issue-export

React Native QA plugin that continuously records the screen and, on device shake, packages the last ~30 seconds of video together with device info and custom app state into a shareable `.zip` file.

## Features

- 🎥 **Continuous screen recording** buffer (last ~30 seconds)
- 📱 **Shake to export** — no UI needed
- 📋 **Automatic device info** (model, OS version, app version)
- 🔌 **Custom app state callback** — attach Redux state, navigation route, user info, etc.
- 📦 **Single .zip output** — ready to share via email, Slack, Jira, etc.

## Platform Support

| Feature | iOS | Android |
|---|---|---|
| Screen Recording | ReplayKit `startClipBuffering` | MediaProjection + MediaRecorder |
| Buffer Strategy | Dual 15s export + AVComposition | 10s chunk rotation (4 chunks) |
| Min OS Version | iOS 15.0 | API 24 (Android 7.0) |
| Shake Detection | UIWindow `motionEnded` swizzle | Accelerometer SensorManager |

## Installation

```bash
npm install fast-issue-export react-native-zip-archive react-native-fs
# or
yarn add fast-issue-export react-native-zip-archive react-native-fs
```

### iOS

```bash
cd ios && pod install
```

> **Note:** Requires iOS 15.0+. Screen recording requires a **physical device** — it will not work on the iOS Simulator.

### Android

Add the following to your app's `AndroidManifest.xml` (inside `<application>`):

```xml
<!-- Already declared by the library, but your app MUST also declare these permissions: -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
```

Register the package in your `MainApplication.kt`:

```kotlin
import com.fastissueexport.FastIssueExportPackage

// Inside getPackages():
packages.add(FastIssueExportPackage())
```

## Usage

```typescript
import { initialize, teardown } from 'fast-issue-export';

// In your root App component or index.js:
initialize({
  // Called on every shake to collect your app's current state
  getAppState: async () => ({
    userId: auth.currentUser?.id,
    screen: navigation.getCurrentRoute()?.name,
    reduxState: store.getState(),
  }),

  // Called when the .zip is ready
  onBugReportReady: (zipPath) => {
    // Open share sheet, upload to server, etc.
    Share.open({ url: `file://${zipPath}` });
  },

  // Optional: handle errors
  onError: (error) => {
    console.error('Bug report failed:', error);
  },

  // Start buffering immediately (default: true)
  autoStart: true,
});

// When shutting down:
teardown();
```

## API Reference

### `initialize(config)`

Start the bug reporter. Begins screen recording buffer and shake detection.

| Option | Type | Default | Description |
|---|---|---|---|
| `getAppState` | `() => Promise<Record<string, unknown>>` | — | Callback to collect custom app data on shake |
| `onBugReportReady` | `(zipPath: string) => void` | — | Called with the path to the generated .zip |
| `onError` | `(error: Error) => void` | — | Called on export failure |
| `autoStart` | `boolean` | `true` | Start buffering immediately |

### `startBuffering(): Promise<void>`

Manually start the screen recording buffer.

### `stopBuffering(): Promise<void>`

Stop buffering and release native resources.

### `exportBugReport(): Promise<string>`

Programmatically trigger a bug report export (same as shaking). Returns the path to the `.zip` file.

### `teardown(): Promise<void>`

Stop everything and clean up. Call on app shutdown.

## Output ZIP Contents

```
bug_report_1709123456789.zip
├── video.mp4          # Last ~30 seconds of screen recording
├── device_info.json   # Auto-generated device/app info
└── app_state.json     # Custom state from getAppState callback
```

### `device_info.json` example

```json
{
  "platform": "ios",
  "deviceModel": "iPhone14,5",
  "systemVersion": "16.5",
  "appVersion": "2.1.0",
  "appBuild": "42",
  "timestamp": "2026-02-28T10:30:00Z"
}
```

## How It Works

### iOS

Uses **ReplayKit's `startClipBuffering`** (iOS 15+) to maintain a rolling video buffer. On export, two 15-second clips are extracted via `exportClip` and concatenated using `AVMutableComposition` to produce ~30 seconds of video.

### Android

Runs a **Foreground Service** with `MediaProjection` + `MediaRecorder`. Records in 10-second chunks, keeping the most recent 4 chunks (~40s). On export, chunks are concatenated using `MediaExtractor` + `MediaMuxer` into a single `.mp4`.

## Requirements

- React Native ≥ 0.72
- iOS 15.0+ (physical device only)
- Android API 24+
- Peer dependencies: `react-native-zip-archive`, `react-native-fs`

## License

MIT
