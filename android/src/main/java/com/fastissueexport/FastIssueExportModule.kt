package com.fastissueexport

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import kotlin.math.sqrt

class FastIssueExportModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext), SensorEventListener {

    companion object {
        const val NAME = "FastIssueExport"
        private const val MEDIA_PROJECTION_REQUEST_CODE = 9001
        private const val SHAKE_THRESHOLD = 12.0f
        private const val SHAKE_COOLDOWN_MS = 2000L
    }

    private var screenRecorderService: ScreenRecorderService? = null
    private var isBound = false
    private var pendingStartPromise: Promise? = null
    private var pendingSavePromise: Promise? = null
    private var sensorManager: SensorManager? = null
    private var lastShakeTime = 0L

    // Service connection
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ScreenRecorderService.LocalBinder
            screenRecorderService = binder.getService()
            isBound = true
            // If we have a pending start, launch the projection request now
            pendingStartPromise?.let { requestMediaProjection(it) }
            pendingStartPromise = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            screenRecorderService = null
            isBound = false
        }
    }

    override fun getName(): String = NAME

    // ─── Buffering ──────────────────────────────────────────────

    @ReactMethod
    fun startBuffering(promise: Promise) {
        val context = reactApplicationContext

        // Start and bind to the foreground service
        val serviceIntent = Intent(context, ScreenRecorderService::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }

        pendingStartPromise = promise
        context.bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun requestMediaProjection(promise: Promise) {
        val activity = currentActivity
        if (activity == null) {
            promise.reject("ERR_NO_ACTIVITY", "No current activity available.")
            return
        }

        val projectionManager =
            activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val captureIntent = projectionManager.createScreenCaptureIntent()

        val listener = object : BaseActivityEventListener() {
            override fun onActivityResult(
                activity: Activity?, requestCode: Int, resultCode: Int, data: Intent?
            ) {
                if (requestCode != MEDIA_PROJECTION_REQUEST_CODE) return
                reactApplicationContext.removeActivityEventListener(this)

                if (resultCode != Activity.RESULT_OK || data == null) {
                    promise.reject("ERR_PERMISSION_DENIED", "User denied screen capture permission.")
                    return
                }

                val mediaProjection = (activity?.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                        as? MediaProjectionManager)?.getMediaProjection(resultCode, data)

                if (mediaProjection == null) {
                    promise.reject("ERR_PROJECTION", "Failed to obtain MediaProjection.")
                    return
                }

                screenRecorderService?.startRecording(mediaProjection, activity!!)
                promise.resolve(null)
            }
        }

        reactApplicationContext.addActivityEventListener(listener)
        activity.startActivityForResult(captureIntent, MEDIA_PROJECTION_REQUEST_CODE)
    }

    @ReactMethod
    fun stopBuffering(promise: Promise) {
        try {
            screenRecorderService?.stopRecording()
            if (isBound) {
                reactApplicationContext.unbindService(serviceConnection)
                isBound = false
            }
            val serviceIntent = Intent(reactApplicationContext, ScreenRecorderService::class.java)
            reactApplicationContext.stopService(serviceIntent)
            screenRecorderService = null
            promise.resolve(null)
        } catch (e: Exception) {
            promise.reject("ERR_STOP_BUFFERING", e.message, e)
        }
    }

    // ─── Save Clip ──────────────────────────────────────────────

    @ReactMethod
    fun saveClip(promise: Promise) {
        val service = screenRecorderService
        if (service == null) {
            promise.reject("ERR_NOT_BUFFERING", "Buffering is not active. Call startBuffering() first.")
            return
        }

        try {
            val outputPath = service.saveClip()
            promise.resolve(outputPath)
        } catch (e: Exception) {
            promise.reject("ERR_SAVE_CLIP", e.message, e)
        }
    }

    // ─── Device Info ────────────────────────────────────────────

    @ReactMethod
    fun getDeviceInfo(promise: Promise) {
        try {
            val context = reactApplicationContext
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)

            val info = Arguments.createMap().apply {
                putString("platform", "android")
                putString("deviceModel", Build.MODEL)
                putString("deviceManufacturer", Build.MANUFACTURER)
                putString("deviceBrand", Build.BRAND)
                putString("systemVersion", Build.VERSION.RELEASE)
                putInt("sdkVersion", Build.VERSION.SDK_INT)
                putString("appVersion", packageInfo.versionName ?: "Unknown")
                putString(
                    "appBuild",
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        packageInfo.longVersionCode.toString()
                    } else {
                        @Suppress("DEPRECATION")
                        packageInfo.versionCode.toString()
                    }
                )
                putString("timestamp", java.text.SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ssXXX",
                    java.util.Locale.US
                ).format(java.util.Date()))
            }

            promise.resolve(info)
        } catch (e: Exception) {
            promise.reject("ERR_DEVICE_INFO", e.message, e)
        }
    }

    // ─── Shake Detection ────────────────────────────────────────

    @ReactMethod
    fun enableShakeDetection() {
        sensorManager = reactApplicationContext
            .getSystemService(Context.SENSOR_SERVICE) as SensorManager

        val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensorManager?.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
    }

    @ReactMethod
    fun disableShakeDetection() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        val acceleration = sqrt((x * x + y * y + z * z).toDouble()).toFloat()

        if (acceleration > SHAKE_THRESHOLD) {
            val now = System.currentTimeMillis()
            if (now - lastShakeTime > SHAKE_COOLDOWN_MS) {
                lastShakeTime = now
                sendEvent("onShakeDetected", null)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }

    // ─── Event Helpers ──────────────────────────────────────────

    private fun sendEvent(eventName: String, params: WritableMap?) {
        reactApplicationContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(eventName, params)
    }
}
