package com.example.ahdcamera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import com.quectel.qcarapi.cb.IQCarRecorderVideoPathCB
import com.quectel.qcarapi.recorder.QCarEncParam
import com.quectel.qcarapi.recorder.QCarRecorder
import com.quectel.qcarapi.recorder.QCarRecorderSetting
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class AhdRecordService : Service() {

    companion object {
        private const val TAG = "AhdRecordService"
        const val CHANNEL_ID = "ahd_record_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.ahdcamera.action.START_RECORDING"
        const val ACTION_STOP = "com.example.ahdcamera.action.STOP_RECORDING"

        const val EXTRA_CHANNEL = "extra_channel"
        const val EXTRA_CSI_NUM = "extra_csi_num"
        const val EXTRA_WIDTH = "extra_width"
        const val EXTRA_HEIGHT = "extra_height"
        const val EXTRA_FPS = "extra_fps"
        const val EXTRA_MIME = "extra_mime"

        // 5-minute segments in milliseconds (5 * 60 * 1000 = 300,000 ms)
        const val SEGMENT_DURATION_MS = 300_000

        // Observable state for UI
        private val _isRecording = MutableStateFlow(false)
        val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

        private val _elapsedSeconds = MutableStateFlow(0)
        val elapsedSeconds: StateFlow<Int> = _elapsedSeconds.asStateFlow()

        private val _segmentCount = MutableStateFlow(0)
        val segmentCount: StateFlow<Int> = _segmentCount.asStateFlow()

        private val _statusMessage = MutableStateFlow("")
        val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

        fun startRecording(
            context: Context,
            channel: Int = 1,
            csiNum: Int = 0,
            width: Int = 1280,
            height: Int = 720,
            fps: Int = 25,
            mimeType: String = "video/avc"
        ) {
            val intent = Intent(context, AhdRecordService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CHANNEL, channel)
                putExtra(EXTRA_CSI_NUM, csiNum)
                putExtra(EXTRA_WIDTH, width)
                putExtra(EXTRA_HEIGHT, height)
                putExtra(EXTRA_FPS, fps)
                putExtra(EXTRA_MIME, mimeType)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "startForegroundService failed, falling back to startService: ${e.message}")
                context.startService(intent)
            }
        }

        fun stopRecording(context: Context) {
            val intent = Intent(context, AhdRecordService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var qCarRecorder: QCarRecorder? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var timerJob: Job? = null
    private var recordStartTime = 0L

    private var activeChannel = 1
    private var activeCsiNum = 0
    private var activeWidth = 1280
    private var activeHeight = 720
    private var activeFps = 25
    private var activeMimeType = "video/avc"

    private var currentSegmentIndex = 0
    private var lastRecordedFile: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        Log.i(TAG, "AhdRecordService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        when (action) {
            ACTION_START -> {
                activeChannel = intent?.getIntExtra(EXTRA_CHANNEL, 1) ?: 1
                activeCsiNum = intent?.getIntExtra(EXTRA_CSI_NUM, 0) ?: 0
                activeWidth = intent?.getIntExtra(EXTRA_WIDTH, 1280) ?: 1280
                activeHeight = intent?.getIntExtra(EXTRA_HEIGHT, 720) ?: 720
                activeFps = intent?.getIntExtra(EXTRA_FPS, 25) ?: 25
                activeMimeType = intent?.getStringExtra(EXTRA_MIME) ?: "video/avc"

                startForeground(NOTIFICATION_ID, buildNotification(0, 1))
                startHardwareRecording()
            }
            ACTION_STOP -> {
                stopHardwareRecording()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AhdCamera:ContinuousRecordWakeLock")?.apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L) // 12 hours max safety timeout
                Log.d(TAG, "PARTIAL_WAKE_LOCK acquired")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "PARTIAL_WAKE_LOCK released")
                }
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing wake lock", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AHD Continuous Recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Status bar indicator for ongoing continuous AHD video recording"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(elapsedSecs: Int, segment: Int): Notification {
        val mins = elapsedSecs / 60
        val secs = elapsedSecs % 60
        val timerString = String.format(Locale.getDefault(), "%02d:%02d", mins, secs)

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val openAppPendingIntent = PendingIntent.getActivity(this, 0, openAppIntent, flags)

        val stopIntent = Intent(this, AhdRecordService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, flags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AHD Camera: Continuous Recording")
            .setContentText("REC $timerString | Segment #$segment (5-min loop)")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .setContentIntent(openAppPendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startHardwareRecording() {
        if (_isRecording.value) {
            Log.w(TAG, "Recording is already active")
            return
        }

        try {
            ExternalCameraHelper.openInitCamera(activeCsiNum)
            val qCarCamera = ExternalCameraHelper.getQCamera(activeCsiNum)
            if (qCarCamera == null) {
                _statusMessage.value = "Error: QCarCamera instance not found"
                Log.e(TAG, "Cannot start recording: QCarCamera is null")
                stopSelf()
                return
            }

            val storageDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "AHDCamera")
            if (!storageDir.exists()) storageDir.mkdirs()

            // Initialize Quectel QCarRecorderSetting
            try {
                val recorderSetting = QCarRecorderSetting()
                recorderSetting.setRootStoragePath(storageDir.absolutePath)
                recorderSetting.setCapacityLeftInSdcard(500) // Keep at least 500 MB free
            } catch (e: Throwable) {
                Log.w(TAG, "QCarRecorderSetting config warning: ${e.message}")
            }

            val recorder = QCarRecorder(this)
            recorder.setQCarCamera(qCarCamera)
            recorder.setAudioEnable(false) // Pure video recording from AHD feed

            val alignedWidth = ((activeWidth / 16) * 16).coerceAtLeast(320)
            val alignedHeight = ((activeHeight / 16) * 16).coerceAtLeast(240)

            val encVideoParam = QCarEncParam.EncVideoParam().apply {
                setResolution(alignedWidth, alignedHeight)
                setCsiNumAndVideoChannel(activeCsiNum, activeChannel)
                setEncoderMineType(activeMimeType)
                setKeyFrameRate(activeFps)
                setKeyIFrameInterval(1) // 1 second keyframe interval for seamless 5-min cuts
                setBitrate(alignedWidth * alignedHeight * 4)
                setBitrateMode(1) // 1 = VBR (fixes error -38 on Qualcomm OMX.qcom.video.encoder.avc)
                setStreamOutputFormat(0) // MPEG-4
            }
            recorder.setMainEncVideoParam(encVideoParam)

            // Configure 5-minute automatic segment splitting (1 = time-based, 300,000 ms = 5 mins)
            recorder.setFileSegmentThreshold(1, SEGMENT_DURATION_MS)

            currentSegmentIndex = 0
            _segmentCount.value = 1

            // Callback that delivers sequentially named output paths and reports completed segments
            recorder.registerRecorderVideoPathCB(object : IQCarRecorderVideoPathCB {
                override fun getRecorderVideoPath(
                    context: Context?,
                    csiNum: Int,
                    param: QCarEncParam.EncVideoParam?
                ): String {
                    currentSegmentIndex++
                    _segmentCount.value = currentSegmentIndex

                    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val fileName = "VID_${timeStamp}_ch${activeChannel}_seg${String.format(Locale.US, "%03d", currentSegmentIndex)}.mp4"
                    val file = File(storageDir, fileName)
                    lastRecordedFile = file

                    Log.i(TAG, "Next 5-minute segment path requested -> ${file.absolutePath}")
                    return file.absolutePath
                }

                override fun getRecorderLockVideoPath(
                    context: Context?,
                    csiNum: Int,
                    param: QCarEncParam.EncVideoParam?
                ): String {
                    val emergencyDir = File(storageDir, "Emergency")
                    if (!emergencyDir.exists()) emergencyDir.mkdirs()
                    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    return File(emergencyDir, "LOCK_${timeStamp}_ch${activeChannel}.mp4").absolutePath
                }

                override fun notifyRecoderVideoResult(
                    param: QCarEncParam.EncVideoParam?,
                    filePath: String?
                ) {
                    Log.i(TAG, "5-minute segment finalized: $filePath")
                    if (!filePath.isNullOrEmpty()) {
                        val completedFile = File(filePath)
                        if (completedFile.exists() && completedFile.length() > 0L) {
                            registerMediaWithSystem(completedFile, "video/mp4")
                            _statusMessage.value = "Saved segment: ${completedFile.name}"
                        }
                    }
                }
            })

            try {
                recorder.startRecorder()
            } catch (e: Throwable) {
                Log.w(TAG, "startRecorder failed with VBR (mode 1), retrying with CBR (mode 2)...", e)
                encVideoParam.setBitrateMode(2) // 2 = CBR
                recorder.setMainEncVideoParam(encVideoParam)
                recorder.startRecorder()
            }
            qCarRecorder = recorder

            recordStartTime = System.currentTimeMillis()
            _isRecording.value = true
            _statusMessage.value = "Continuous Recording Active (5-min loop)"
            Log.i(TAG, "QCarRecorder started successfully on channel $activeChannel")

            // Start elapsed timer loop
            timerJob?.cancel()
            timerJob = serviceScope.launch {
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                while (isActive && _isRecording.value) {
                    val elapsed = ((System.currentTimeMillis() - recordStartTime) / 1000).toInt()
                    _elapsedSeconds.value = elapsed

                    // Update notification every 2 seconds
                    if (elapsed % 2 == 0) {
                        notificationManager?.notify(NOTIFICATION_ID, buildNotification(elapsed, _segmentCount.value))
                    }
                    delay(1000L)
                }
            }

        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start hardware recording", e)
            _isRecording.value = false
            _statusMessage.value = "Record error: ${e.localizedMessage}"
            stopSelf()
        }
    }

    private fun stopHardwareRecording() {
        timerJob?.cancel()
        timerJob = null

        try {
            qCarRecorder?.stopRecorder()
            Log.i(TAG, "QCarRecorder stopped")
        } catch (e: Throwable) {
            Log.e(TAG, "Error stopping QCarRecorder", e)
        } finally {
            qCarRecorder = null
            _isRecording.value = false
            _statusMessage.value = "Recording stopped"
        }

        // Scan the final segment file if existing
        lastRecordedFile?.let { file ->
            if (file.exists() && file.length() > 0L) {
                registerMediaWithSystem(file, "video/mp4")
            }
        }
    }

    private fun registerMediaWithSystem(file: File, mimeType: String) {
        try {
            MediaScannerConnection.scanFile(
                applicationContext,
                arrayOf(file.absolutePath),
                arrayOf(mimeType)
            ) { path, uri ->
                Log.d(TAG, "Scanned $path into MediaStore: $uri")
            }

            val values = ContentValues().apply {
                put(MediaStore.Video.Media.TITLE, file.nameWithoutExtension)
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, mimeType)
                put(MediaStore.Video.Media.DATA, file.absolutePath)
                put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
            }
            contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore insertion: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopHardwareRecording()
        releaseWakeLock()
        serviceScope.cancel()
        Log.i(TAG, "AhdRecordService destroyed")
    }
}
