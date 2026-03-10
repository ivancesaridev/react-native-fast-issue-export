/**
 * fast-issue-export
 *
 * React Native QA plugin: continuous screen recording buffer + shake-to-export.
 * Packages video, device info, and custom app state into a shareable .zip.
 */

import {
    NativeModules,
    NativeEventEmitter,
    DeviceEventEmitter,
    Platform,
} from 'react-native';
import RNFS from 'react-native-fs';

// react-native-zip-archive is a peer dependency
const { zip } = require('react-native-zip-archive') as {
    zip: (source: string | string[], target: string) => Promise<string>;
};

// ─── Types ──────────────────────────────────────────────────────

export interface FastIssueExportConfig {
    /**
     * Callback invoked on shake to collect custom JSON from the host app.
     * Return any serializable object (Redux state, navigation route, user info, etc.).
     */
    getAppState?: () => Promise<Record<string, unknown>>;

    /**
     * Called when the .zip bug report is ready.
     * Receives the absolute path to the zip file.
     * Use this to open a share sheet, upload, etc.
     */
    onBugReportReady?: (zipPath: string) => void;

    /**
     * Called if an error occurs during the export flow.
     */
    onError?: (error: Error) => void;

    /**
     * Whether to start buffering automatically on initialize(). Default: true.
     */
    autoStart?: boolean;
}

export interface DeviceInfo {
    platform: 'ios' | 'android';
    deviceModel: string;
    systemVersion: string;
    appVersion: string;
    appBuild: string;
    timestamp: string;
    [key: string]: unknown;
}

// ─── Native Module ──────────────────────────────────────────────

interface FastIssueExportNative {
    startBuffering(): Promise<void>;
    stopBuffering(): Promise<void>;
    saveClip(): Promise<string>; // returns video file path
    getDeviceInfo(): Promise<DeviceInfo>;
    enableShakeDetection(): void;
    disableShakeDetection(): void;
    shareFile(filePath: string): Promise<void>;
}

const NativeModule: FastIssueExportNative = NativeModules.FastIssueExport;

if (!NativeModule) {
    throw new Error(
        '[fast-issue-export] Native module not found. ' +
        'Make sure you ran `pod install` (iOS) and rebuilt the app.'
    );
}

const eventEmitter = Platform.OS === 'ios'
    ? new NativeEventEmitter(NativeModules.FastIssueExport)
    : DeviceEventEmitter;

// ─── State ──────────────────────────────────────────────────────

let config: FastIssueExportConfig = {};
let shakeSubscription: { remove: () => void } | null = null;
let isExporting = false;

// ─── Public API ─────────────────────────────────────────────────

/**
 * Initialize the bug reporter.
 *
 * Call this once at app startup (e.g. in your root App component or index.js).
 * It will start buffering the screen and listening for shake gestures.
 *
 * @example
 * ```ts
 * import { initialize } from 'fast-issue-export';
 *
 * initialize({
 *   getAppState: async () => ({
 *     userId: auth.currentUser?.id,
 *     screen: navigation.getCurrentRoute(),
 *   }),
 *   onBugReportReady: (zipPath) => {
 *     Share.open({ url: `file://${zipPath}` });
 *   },
 * });
 * ```
 */
export async function initialize(cfg: FastIssueExportConfig = {}): Promise<void> {
    config = cfg;

    // Register shake listener
    shakeSubscription?.remove();
    shakeSubscription = eventEmitter.addListener('onShakeDetected', handleShake);

    NativeModule.enableShakeDetection();

    if (cfg.autoStart !== false) {
        await startBuffering();
    }
}

/**
 * Start the continuous screen recording buffer.
 * Called automatically by `initialize()` unless `autoStart: false`.
 */
export async function startBuffering(): Promise<void> {
    return NativeModule.startBuffering();
}

/**
 * Stop buffering and release resources.
 */
export async function stopBuffering(): Promise<void> {
    NativeModule.disableShakeDetection();
    shakeSubscription?.remove();
    shakeSubscription = null;
    return NativeModule.stopBuffering();
}

/**
 * Manually trigger a bug report export (same as shaking the device).
 * Returns the path to the generated .zip file.
 */
export async function exportBugReport(): Promise<string> {
    return performExport();
}

/**
 * Clean up. Call this when unmounting / shutting down.
 */
export async function teardown(): Promise<void> {
    await stopBuffering();
}

/**
 * Share a file (e.g. a bug report ZIP) using the native share sheet.
 * On Android uses FileProvider + Intent.ACTION_SEND.
 * On iOS uses the standard Share API.
 */
export async function shareReport(filePath: string): Promise<void> {
    if (Platform.OS === 'android') {
        return NativeModule.shareFile(filePath);
    }
    // iOS: React Native's Share API supports file URLs
    const { Share } = require('react-native');
    await Share.share({ url: filePath, title: 'Bug Report' });
}

// ─── Internal ───────────────────────────────────────────────────

async function handleShake(): Promise<void> {
    if (isExporting) return; // Prevent double-invocation
    try {
        const zipPath = await performExport();
        config.onBugReportReady?.(zipPath);
    } catch (error) {
        config.onError?.(error instanceof Error ? error : new Error(String(error)));
    }
}

async function performExport(): Promise<string> {
    isExporting = true;

    try {
        const tempDir = `${RNFS.CachesDirectoryPath}/fast_issue_export_${Date.now()}`;
        await RNFS.mkdir(tempDir);

        // 1. Save video clip from native buffer
        const videoPath = await NativeModule.saveClip();

        // Copy video into our temp directory
        const videoFileName = 'video.mp4';
        const videoDest = `${tempDir}/${videoFileName}`;
        await RNFS.copyFile(videoPath, videoDest);

        // 2. Collect device info
        const deviceInfo = await NativeModule.getDeviceInfo();
        const deviceInfoPath = `${tempDir}/device_info.json`;
        await RNFS.writeFile(deviceInfoPath, JSON.stringify(deviceInfo, null, 2), 'utf8');

        // 3. Collect custom app state (if callback provided)
        if (config.getAppState) {
            try {
                const appState = await config.getAppState();
                const appStatePath = `${tempDir}/app_state.json`;
                await RNFS.writeFile(appStatePath, JSON.stringify(appState, null, 2), 'utf8');
            } catch (err) {
                // Write error info instead of crashing the whole export
                const errorPath = `${tempDir}/app_state_error.json`;
                await RNFS.writeFile(
                    errorPath,
                    JSON.stringify({
                        error: 'Failed to collect app state',
                        message: err instanceof Error ? err.message : String(err),
                    }, null, 2),
                    'utf8',
                );
            }
        }

        // 4. Create ZIP archive
        const zipPath = `${RNFS.CachesDirectoryPath}/bug_report_${Date.now()}.zip`;
        await zip(tempDir, zipPath);

        // 5. Clean up temp directory
        await RNFS.unlink(tempDir).catch(() => { });

        return zipPath;
    } finally {
        isExporting = false;
    }
}
