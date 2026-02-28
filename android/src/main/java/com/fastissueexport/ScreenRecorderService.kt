package com.fastissueexport

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.File
import java.nio.ByteBuffer

/**
 * Foreground service that continuously records the screen in 10-second chunks
 * and keeps the last 4 chunks (~40s) for a rolling ~30s buffer.
 */
class ScreenRecorderService : Service() {

    companion object {
        private const val CHANNEL_ID = "fast_issue_export_recording"
        private const val NOTIFICATION_ID = 7001
        private const val CHUNK_DURATION_MS = 10_000L // 10 seconds per chunk
        private const val MAX_CHUNKS = 4              // keep last 4 chunks (~40s)
        private const val VIDEO_BIT_RATE = 6_000_000
        private const val VIDEO_FRAME_RATE = 30
    }

    inner class LocalBinder : Binder() {
        fun getService(): ScreenRecorderService = this@ScreenRecorderService
    }

    private val binder = LocalBinder()
    private var mediaProjection: MediaProjection? = null
    private var mediaRecorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var persistentSurface: android.view.Surface? = null
    private var handler: Handler? = null
    private var isRecording = false
    private var chunkIndex = 0
    private val chunkFiles = mutableListOf<File>()
    private var screenWidth = 720
    private var screenHeight = 1280
    private var screenDensity = 1

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        handler = Handler(Looper.getMainLooper())
    }

    override fun onDestroy() {
        stopRecording()
        cleanupChunks()
        super.onDestroy()
    }

    // ─── Public API ─────────────────────────────────────────────

    fun promoteToForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    fun startRecording(projection: MediaProjection, activity: Activity) {
        mediaProjection = projection

        // Get screen dimensions
        val windowManager = activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        // Scale down for performance (max 720p width)
        if (screenWidth > 720) {
            val ratio = 720f / screenWidth
            screenWidth = 720
            screenHeight = (screenHeight * ratio).toInt()
            // Ensure height is even (required by encoder)
            if (screenHeight % 2 != 0) screenHeight += 1
        }

        isRecording = true
        
        isRecording = true
        
        // Setup persistent surface for VirtualDisplay on Android M+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                persistentSurface = MediaCodec.createPersistentInputSurface()
                android.util.Log.d("FastIssueExport", "Created persistent input surface")
                
                // 1. Prepare first recorder (gets it ready to consume frames)
                prepareFirstRecorder()

                // 2. Register callback
                mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        super.onStop()
                        android.util.Log.d("FastIssueExport", "MediaProjection stopped by system")
                        stopRecording()
                    }
                }, handler)

                // 3. Create virtual display (starts feeding frames to persistentSurface)
                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "FastIssueExport",
                    screenWidth, screenHeight, screenDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    persistentSurface, null, handler
                )
                android.util.Log.d("FastIssueExport", "Created virtual display: ${screenWidth}x${screenHeight}")
                
                // 4. Start recording
                mediaRecorder?.start()
                android.util.Log.d("FastIssueExport", "Started first recording chunk")
            } catch (e: Exception) {
                android.util.Log.e("FastIssueExport", "Failed to init recording: ${e.message}", e)
                isRecording = false
                return
            }
        } else {
            // Fallback for older devices
            startNewChunk()
        }

        scheduleChunkRotation()
    }

    private fun prepareFirstRecorder() {
        val chunkFile = File(cacheDir, "chunk_0.mp4")
        chunkFiles.add(chunkFile)
        chunkIndex = 1

        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        mediaRecorder?.apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(screenWidth, screenHeight)
            setVideoFrameRate(VIDEO_FRAME_RATE)
            setVideoEncodingBitRate(VIDEO_BIT_RATE)
            setOutputFile(chunkFile.absolutePath)
            if (persistentSurface != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setInputSurface(persistentSurface!!)
            }
            prepare()
        }
    }

    fun stopRecording() {
        isRecording = false
        handler?.removeCallbacksAndMessages(null)
        stopCurrentRecorder()
        
        // Full cleanup for persistent session
        virtualDisplay?.release()
        virtualDisplay = null
        persistentSurface?.release()
        persistentSurface = null
        mediaProjection?.stop()
        mediaProjection = null
    }

    /**
     * Concatenates the buffered chunks into a single .mp4 and returns its path.
     * Trims to the last 30 seconds if the total duration exceeds that.
     */
    fun saveClip(): String {
        // Temporarily stop and finalize the current chunk
        stopCurrentRecorder()

        if (chunkFiles.isEmpty()) {
            throw IllegalStateException("No recorded chunks available.")
        }

        val outputFile = File(cacheDir, "bug_report_${System.currentTimeMillis()}.mp4")
        concatenateChunks(chunkFiles.toList(), outputFile)

        // Resume recording
        if (isRecording) {
            startNewChunk()
            scheduleChunkRotation()
        }

        return outputFile.absolutePath
    }

    // ─── Chunk Management ───────────────────────────────────────

    private fun startNewChunk() {
        val chunkFile = File(cacheDir, "chunk_${chunkIndex}.mp4")
        chunkIndex++
        android.util.Log.d("FastIssueExport", "Starting new chunk: ${chunkFile.name}")

        try {
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder?.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(screenWidth, screenHeight)
                setVideoFrameRate(VIDEO_FRAME_RATE)
                setVideoEncodingBitRate(VIDEO_BIT_RATE)
                setOutputFile(chunkFile.absolutePath)
                if (persistentSurface != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setInputSurface(persistentSurface!!)
                }
                prepare()
            }

            if (persistentSurface == null) {
                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "FastIssueExport",
                    screenWidth, screenHeight, screenDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    mediaRecorder?.surface, null, handler
                )
            }

            mediaRecorder?.start()
            chunkFiles.add(chunkFile)

            // Purge old chunks beyond MAX_CHUNKS
            while (chunkFiles.size > MAX_CHUNKS) {
                val old = chunkFiles.removeAt(0)
                old.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopCurrentRecorder() {
        try {
            mediaRecorder?.stop()
            android.util.Log.d("FastIssueExport", "Stopped current recorder")
        } catch (e: Exception) {
            android.util.Log.w("FastIssueExport", "Failed to stop recorder: ${e.message}")
        }
        try {
            mediaRecorder?.release()
        } catch (_: Exception) {}
        mediaRecorder = null
        if (persistentSurface == null) {
            virtualDisplay?.release()
            virtualDisplay = null
        }
    }

    private fun scheduleChunkRotation() {
        handler?.postDelayed({
            if (isRecording) {
                stopCurrentRecorder()
                startNewChunk()
                scheduleChunkRotation()
            }
        }, CHUNK_DURATION_MS)
    }

    private fun cleanupChunks() {
        chunkFiles.forEach { it.delete() }
        chunkFiles.clear()
    }

    // ─── Video Concatenation ────────────────────────────────────

    private fun concatenateChunks(chunks: List<File>, output: File) {
        if (chunks.isEmpty()) return

        // Find the first valid chunk's format to use as template
        val firstExtractor = MediaExtractor()
        firstExtractor.setDataSource(chunks.first().absolutePath)
        val trackIndex = findVideoTrack(firstExtractor)
        if (trackIndex < 0) {
            firstExtractor.release()
            throw IllegalStateException("No video track found in recorded chunks.")
        }
        firstExtractor.selectTrack(trackIndex)
        val format = firstExtractor.getTrackFormat(trackIndex)
        firstExtractor.release()

        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val muxerTrackIndex = muxer.addTrack(format)
        muxer.start()

        var timeOffsetUs = 0L
        val buffer = ByteBuffer.allocate(1024 * 1024) // 1MB buffer
        val bufferInfo = MediaCodec.BufferInfo()

        for (chunk in chunks) {
            if (!chunk.exists() || chunk.length() == 0L) {
                android.util.Log.w("FastIssueExport", "Skipping empty/missing chunk: ${chunk.name}")
                continue
            }
            android.util.Log.d("FastIssueExport", "Concatenating chunk: ${chunk.name} (${chunk.length()} bytes)")

            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(chunk.absolutePath)
                val vTrack = findVideoTrack(extractor)
                if (vTrack < 0) continue

                extractor.selectTrack(vTrack)
                var lastPts = 0L

                while (true) {
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break

                    bufferInfo.offset = 0
                    bufferInfo.size = sampleSize
                    bufferInfo.presentationTimeUs = extractor.sampleTime + timeOffsetUs
                    bufferInfo.flags = extractor.sampleFlags

                    lastPts = extractor.sampleTime

                    muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                    extractor.advance()
                }

                // Offset for next chunk = last PTS + one frame duration
                timeOffsetUs += lastPts + (1_000_000L / VIDEO_FRAME_RATE)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                extractor.release()
            }
        }

        try {
            muxer.stop()
            muxer.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun findVideoTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("video/") == true) return i
        }
        return -1
    }

    // ─── Notification ───────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active screen recording for bug reporting"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Bug Reporter Active")
                .setContentText("Recording screen for bug reports")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Bug Reporter Active")
                .setContentText("Recording screen for bug reports")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build()
        }
    }
}
