package com.example.ahdcamera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.SurfaceView
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

enum class ResolutionOption(val label: String, val width: Int, val height: Int) {
    RES_720P("720p (1280x720)", 1280, 720),
    RES_480P("480p (854x480)", 854, 480),
    RES_320P("320p (480x320)", 480, 320),
    RES_240P("240p (320x240)", 320, 240)
}

enum class FpsOption(val label: String, val fps: Int) {
    FPS_25("25 FPS (PAL Native)", 25),
    FPS_30("30 FPS (NTSC)", 30),
    FPS_15("15 FPS (Eco)", 15)
}

enum class CodecOption(val label: String, val mimeType: String) {
    H264("H.264 (AVC)", MediaFormat.MIMETYPE_VIDEO_AVC),
    H265("H.265 (HEVC)", MediaFormat.MIMETYPE_VIDEO_HEVC)
}

class AhdRecorderHelper(private val context: Context) {

    companion object {
        private const val TAG = "AhdRecorderHelper"
    }

    var isRecording = AtomicBoolean(false)
        private set

    private var recordingJob: Job? = null
    private var recordStartTime = 0L

    private fun registerMediaWithSystem(file: File, mimeType: String) {
        try {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf(mimeType)
            ) { path, uri ->
                Log.d(TAG, "Scanned $path into MediaStore: $uri")
            }

            val values = ContentValues().apply {
                if (mimeType.startsWith("video")) {
                    put(MediaStore.Video.Media.TITLE, file.nameWithoutExtension)
                    put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                    put(MediaStore.Video.Media.MIME_TYPE, mimeType)
                    put(MediaStore.Video.Media.DATA, file.absolutePath)
                    put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                    put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
                } else {
                    put(MediaStore.Images.Media.TITLE, file.nameWithoutExtension)
                    put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                    put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                    put(MediaStore.Images.Media.DATA, file.absolutePath)
                    put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                    put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
                }
            }
            val contentUri = if (mimeType.startsWith("video")) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            context.contentResolver.insert(contentUri, values)
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore insert notification: ${e.message}")
        }
    }

    fun capturePhoto(
        externalHelper: ExternalCameraHelper,
        surfaceView: SurfaceView,
        resolution: ResolutionOption,
        onComplete: (Boolean, String) -> Unit
    ) {
        externalHelper.captureBitmap(surfaceView, resolution.width, resolution.height) { bitmap ->
            if (bitmap == null) {
                onComplete(false, "Failed to capture frame snapshot")
                return@captureBitmap
            }

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val fileName = "IMG_$timeStamp.jpg"

                    val publicPicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "AHDCamera")
                    if (!publicPicDir.exists()) publicPicDir.mkdirs()

                    val imageFile = File(publicPicDir, fileName)
                    FileOutputStream(imageFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    }

                    registerMediaWithSystem(imageFile, "image/jpeg")

                    withContext(Dispatchers.Main) {
                        onComplete(true, "Saved: ${imageFile.name}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving photo", e)
                    withContext(Dispatchers.Main) {
                        onComplete(false, "Photo error: ${e.localizedMessage}")
                    }
                }
            }
        }
    }

    private fun bitmapToNv12(bitmap: Bitmap, width: Int, height: Int): ByteArray {
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)

        val yuv = ByteArray(width * height * 3 / 2)
        var yIndex = 0
        var uvIndex = width * height

        var index = 0
        for (j in 0 until height) {
            for (i in 0 until width) {
                val pixel = argb[index++]
                val r = (pixel shr 16) and 0xff
                val g = (pixel shr 8) and 0xff
                val b = pixel and 0xff

                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128

                yuv[yIndex++] = y.coerceIn(0, 255).toByte()
                if (j % 2 == 0 && i % 2 == 0) {
                    // NV12 semi-planar format: U component first, then V component (matches COLOR_FormatYUV420SemiPlanar)
                    yuv[uvIndex++] = u.coerceIn(0, 255).toByte()
                    yuv[uvIndex++] = v.coerceIn(0, 255).toByte()
                }
            }
        }
        return yuv
    }

    fun startVideoRecording(
        externalHelper: ExternalCameraHelper,
        surfaceView: SurfaceView,
        resolution: ResolutionOption,
        fpsOption: FpsOption,
        codecOption: CodecOption,
        onTimerUpdate: (Int) -> Unit,
        onComplete: (Boolean, String) -> Unit
    ) {
        if (isRecording.getAndSet(true)) return

        recordStartTime = System.currentTimeMillis()

        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            var muxer: MediaMuxer? = null
            var encoder: MediaCodec? = null
            var trackIndex = -1
            var isMuxerStarted = false

            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val videoFileName = "VID_$timeStamp.mp4"
            val targetWidth = (resolution.width / 16) * 16
            val targetHeight = (resolution.height / 16) * 16

            var outputFile: File? = null

            try {
                val publicVidDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "AHDCamera")
                if (!publicVidDir.exists()) publicVidDir.mkdirs()

                outputFile = File(publicVidDir, videoFileName)

                val format = MediaFormat.createVideoFormat(codecOption.mimeType, targetWidth, targetHeight).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
                    setInteger(MediaFormat.KEY_BIT_RATE, targetWidth * targetHeight * 4)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fpsOption.fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }

                encoder = MediaCodec.createEncoderByType(codecOption.mimeType)
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoder.start()

                muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

                val bufferInfo = MediaCodec.BufferInfo()

                val timerJob = launch(Dispatchers.Main) {
                    while (isActive && isRecording.get()) {
                        val elapsedSeconds = ((System.currentTimeMillis() - recordStartTime) / 1000).toInt()
                        onTimerUpdate(elapsedSeconds)
                        delay(500)
                    }
                }

                while (isActive && isRecording.get()) {
                    val frameStart = System.currentTimeMillis()

                    var capturedBitmap: Bitmap? = null
                    val latch = java.util.concurrent.CountDownLatch(1)

                    withContext(Dispatchers.Main) {
                        externalHelper.captureBitmap(surfaceView, targetWidth, targetHeight) { bmp ->
                            capturedBitmap = bmp
                            latch.countDown()
                        }
                    }

                    latch.await(300, java.util.concurrent.TimeUnit.MILLISECONDS)

                    val bmp = capturedBitmap
                    if (bmp != null) {
                        // Calculate real-time PTS in microseconds matching actual capture time
                        val ptsUs = (System.currentTimeMillis() - recordStartTime) * 1000L

                        // 1. Feed frame into MediaCodec input buffer
                        val inputIndex = encoder.dequeueInputBuffer(10000)
                        if (inputIndex >= 0) {
                            val inputBuffer = encoder.getInputBuffer(inputIndex)
                            if (inputBuffer != null) {
                                inputBuffer.clear()
                                val yuvData = bitmapToNv12(bmp, targetWidth, targetHeight)
                                inputBuffer.put(yuvData)
                                encoder.queueInputBuffer(inputIndex, 0, yuvData.size, ptsUs, 0)
                            }
                        }

                        // 2. Drain encoded output buffers to MediaMuxer
                        var outIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)
                        while (outIndex >= 0) {
                            val encodedData = encoder.getOutputBuffer(outIndex)
                            if (encodedData != null) {
                                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                    bufferInfo.size = 0
                                }
                                if (bufferInfo.size != 0) {
                                    if (!isMuxerStarted) {
                                        val newFormat = encoder.outputFormat
                                        trackIndex = muxer.addTrack(newFormat)
                                        muxer.start()
                                        isMuxerStarted = true
                                    }
                                    encodedData.position(bufferInfo.offset)
                                    encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                    bufferInfo.presentationTimeUs = ptsUs
                                    muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                                }
                            }
                            encoder.releaseOutputBuffer(outIndex, false)
                            outIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
                        }
                    }

                    val elapsed = System.currentTimeMillis() - frameStart
                    val targetFrameIntervalMs = 1000L / fpsOption.fps
                    val sleepMs = targetFrameIntervalMs - elapsed
                    if (sleepMs > 0) delay(sleepMs)
                }

                timerJob.cancel()

                try {
                    encoder?.stop()
                    encoder?.release()
                } catch (e: Exception) {}

                try {
                    if (isMuxerStarted) {
                        muxer?.stop()
                        muxer?.release()
                    }
                } catch (e: Exception) {}

                if (outputFile.exists() && outputFile.length() > 0) {
                    registerMediaWithSystem(outputFile, "video/mp4")
                    withContext(Dispatchers.Main) {
                        onComplete(true, "Recorded: $videoFileName")
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        onComplete(false, "Video recording failed (empty file)")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Recording error", e)
                withContext(Dispatchers.Main) {
                    onComplete(false, "Record error: ${e.localizedMessage}")
                }
            } finally {
                isRecording.set(false)
            }
        }
    }

    fun stopVideoRecording() {
        isRecording.set(false)
    }
}
