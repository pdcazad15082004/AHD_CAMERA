package com.example.ahdcamera

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import com.quectel.qcarapi.stream.QCarCamera
import com.quectel.qcarapi.util.QCarError
import java.util.concurrent.ConcurrentHashMap

class ExternalCameraHelper(private val context: Context) {

    private var isLibraryLoaded = false
    private var isPreviewing = false
    private var currentCsiNum = 0
    private var currentChannel = 1 // Default to Channel 1 (AHD 2)
    companion object {
        private const val TAG = "ExternalCameraHelper"
        private val qCarCameraMap = ConcurrentHashMap<Int, QCarCamera>()
        val openedCsiSet: MutableSet<Int> = java.util.Collections.synchronizedSet(mutableSetOf<Int>())

        private val errorHandler = QCarError.OnErrorCB { errType, errCode, errText, csiNum, channelNum ->
            val msg = if (errText != null) String(errText) else ""
            Log.w(TAG, "QCarError CB -> type:$errType code:$errCode msg:$msg csi:$csiNum ch:$channelNum")
        }

        @Synchronized
        fun getQCamera(csiNum: Int): QCarCamera? {
            if (csiNum >= 3 || csiNum < 0) {
                Log.w(TAG, "csiNum must be between 0 and 2: $csiNum")
                return null
            }
            var camera = qCarCameraMap[csiNum]
            if (camera == null) {
                try {
                    camera = QCarCamera(csiNum)
                    qCarCameraMap[csiNum] = camera
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to instantiate QCarCamera for csi $csiNum", e)
                }
            }
            return camera
        }

        @Synchronized
        fun removeQCamera(csiNum: Int) {
            val camera = qCarCameraMap.remove(csiNum)
            openedCsiSet.remove(csiNum)
            if (camera != null) {
                try {
                    camera.cameraClose()
                    camera.release()
                } catch (e: Throwable) {
                    Log.e(TAG, "Error closing camera for csi $csiNum", e)
                }
            }
        }

        @Synchronized
        fun openInitCamera(csiNum: Int, inputNum: Int = 4, inputType: Int = 86): Boolean {
            if (openedCsiSet.contains(csiNum)) return true

            val qCarCamera = getQCamera(csiNum) ?: return false
            var count = 0
            var success = false

            while (count < 10) {
                Log.i(TAG, "Attempting cameraOpen csiNum=$csiNum inputNum=$inputNum inputType=$inputType (attempt ${count + 1})")
                try {
                    val ret = qCarCamera.cameraOpen(inputNum, inputType)
                    if (ret == 0) {
                        Log.i(TAG, "Open csi $csiNum Success")
                        openedCsiSet.add(csiNum)
                        try {
                            qCarCamera.registerOnErrorCB(errorHandler)
                        } catch (e: Throwable) {
                            Log.w(TAG, "registerOnErrorCB failed: ${e.message}")
                        }
                        success = true
                        break
                    } else {
                        count++
                        Log.e(TAG, "Open Failed, cameraOpen csi $csiNum return = $ret")
                        try {
                            Thread.sleep(500)
                        } catch (e: InterruptedException) {
                            e.printStackTrace()
                        }
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Exception during cameraOpen for csi $csiNum", e)
                    count++
                    try {
                        Thread.sleep(500)
                    } catch (ex: Exception) {}
                }
            }
            return success
        }
    }

    init {
        try {
            System.loadLibrary("mmqcar_qcar_jni")
            isLibraryLoaded = true
            Log.d(TAG, "mmqcar_qcar_jni loaded successfully")
        } catch (e: Throwable) {
            Log.w(TAG, "Could not load mmqcar_qcar_jni native library: ${e.localizedMessage}")
            isLibraryLoaded = false
        }
    }

    fun isAvailable(): Boolean = isLibraryLoaded

    fun startPreview(
        surfaceView: SurfaceView,
        csiNum: Int = 0,
        channel: Int = 1,
        width: Int = 1280,
        height: Int = 720,
        onStatus: (String) -> Unit
    ) {
        currentCsiNum = csiNum
        currentChannel = channel

        if (!isLibraryLoaded) {
            onStatus("Quectel vCVBS Library not loaded")
            return
        }

        try {
            val qCarCamera = getQCamera(csiNum)
            if (qCarCamera == null) {
                onStatus("Failed to obtain QCarCamera instance for CSI $csiNum")
                return
            }

            openInitCamera(csiNum, inputNum = 4, inputType = 86)

            val holder = surfaceView.holder
            val surface = holder.surface

            if (surface == null || !surface.isValid) {
                onStatus("Surface is invalid for preview")
                return
            }

            val format = QCarCamera.YUV420_NV21
            val result = qCarCamera.startPreview(channel, surface, width, height, format)
            isPreviewing = true

            val ahdLabel = "AHD ${channel + 1} (Channel $channel, CSI $csiNum)"
            onStatus("External Tablet Cam ($ahdLabel) Active (${width}x${height})")
            Log.d(TAG, "Started external QCarCamera preview: csi=$csiNum, ch=$channel ($ahdLabel), ${width}x${height}, result=$result")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start QCarCamera preview for ch=$channel", e)
            onStatus("External Cam Error (Ch $channel): ${e.localizedMessage ?: e.javaClass.simpleName}")
        }
    }

    fun stopPreview(channel: Int = currentChannel) {
        if (!isPreviewing) return
        try {
            val qCarCamera = getQCamera(currentCsiNum)
            qCarCamera?.stopPreview(channel)
            isPreviewing = false
            Log.d(TAG, "Stopped QCarCamera preview for ch=$channel")
        } catch (e: Throwable) {
            Log.e(TAG, "Error stopping QCarCamera preview", e)
        }
    }

    fun closeCamera(csiNum: Int = currentCsiNum) {
        try {
            val camera = qCarCameraMap[csiNum]
            if (camera != null) {
                camera.stopPreview(currentChannel)
                camera.cameraClose()
                camera.release()
                qCarCameraMap.remove(csiNum)
                openedCsiSet.remove(csiNum)
                isPreviewing = false
                Log.d(TAG, "Camera closed and released for CSI $csiNum")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error closing camera for CSI $csiNum", e)
        }
    }

    fun closeAllCameras() {
        qCarCameraMap.keys.toList().forEach { csi ->
            closeCamera(csi)
        }
    }

    fun captureBitmap(
        surfaceView: SurfaceView,
        width: Int,
        height: Int,
        onResult: (Bitmap?) -> Unit
    ) {
        val surface = surfaceView.holder.surface
        if (!isPreviewing || surface == null || !surface.isValid || width <= 0 || height <= 0) {
            onResult(null)
            return
        }

        try {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(
                surfaceView,
                bitmap,
                { copyResult ->
                    if (copyResult == PixelCopy.SUCCESS) {
                        onResult(bitmap)
                    } else {
                        Log.w(TAG, "PixelCopy failed with result code: $copyResult")
                        onResult(null)
                    }
                },
                Handler(Looper.getMainLooper())
            )
        } catch (e: Throwable) {
            Log.e(TAG, "PixelCopy exception", e)
            onResult(null)
        }
    }
}
