package com.aimbuddy.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager as MediaProjectionManagerSystem
import android.util.Log
import com.aimbuddy.overlay.PoseOverlayView
import com.aimbuddy.pose.PoseDetector
import java.nio.ByteBuffer

class MediaProjectionManager(private val context: Context, private val overlayView: PoseOverlayView) {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var isRunning = false
    private lateinit var poseDetector: PoseDetector

    fun createScreenCaptureIntent(): Intent {
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManagerSystem
        return manager.createScreenCaptureIntent()
    }

    fun start(resultCode: Int, data: Intent) {
        if (isRunning) return

        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManagerSystem
        mediaProjection = manager.getMediaProjection(resultCode, data)

        val width = 320
        val height = 240

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            image?.let {
                processImage(it)
                it.close()
            }
        }, null)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "PoseDetection",
            width, height, 160,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        isRunning = true
        poseDetector = PoseDetector(context)
        Log.d("MediaProjection", "Started")
    }

    private fun processImage(image: Image) {
        val bitmap = imageToBitmap(image) ?: return
        val keypoints = poseDetector.detect(bitmap)
        bitmap.recycle()
        overlayView.updatePose(keypoints)
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val buffer = image.planes[0].buffer
        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }

    fun stop() {
        isRunning = false
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        Log.d("MediaProjection", "Stopped")
    }
}
