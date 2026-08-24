package com.aimbuddy

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjection.Callback
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_MEDIA_PROJECTION = 1001
        private const val REQUEST_OVERLAY_PERMISSION = 1002
        private const val CAPTURE_WIDTH = 320
        private const val CAPTURE_HEIGHT = 240
    }

    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val imageThread = HandlerThread("image-reader").also { it.start() }
    private val imageHandler = Handler(imageThread.looper)
    private var isRunning = false
    private lateinit var detector: TFLiteDetector

    private external fun nativeInit(assetManager: android.content.res.AssetManager,
                                    screenWidth: Int, screenHeight: Int): Boolean
    private external fun nativeStart()
    private external fun nativeStop()
    private external fun nativeShutdown()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)

        startButton.setOnClickListener { onStartClicked() }
        stopButton.setOnClickListener { onStopClicked() }
        stopButton.isEnabled = false

        enableImmersiveMode()

        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(displayMetrics)
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        detector = TFLiteDetector(this)

        if (!nativeInit(assets, screenWidth, screenHeight)) {
            statusText.text = "Init Failed"
            Toast.makeText(this, "Native init failed", Toast.LENGTH_LONG).show()
        } else {
            statusText.text = "Ready"
        }
    }

    private fun onStartClicked() {
        if (isRunning) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"))
                startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
                return
            }
        }
        requestMediaProjection()
    }

    private fun requestMediaProjection() {
        val intent = mediaProjectionManager?.createScreenCaptureIntent()
        if (intent != null) {
            startActivityForResult(intent, REQUEST_MEDIA_PROJECTION)
        } else {
            Toast.makeText(this, "MediaProjection not available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onStopClicked() {
        stopESP()
    }

    private fun enableImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_OVERLAY_PERMISSION -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                    requestMediaProjection()
                } else {
                    Toast.makeText(this, "Overlay permission required", Toast.LENGTH_SHORT).show()
                }
            }
            REQUEST_MEDIA_PROJECTION -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    startESP(data)
                } else {
                    Toast.makeText(this, "MediaProjection permission required", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startESP(data: Intent) {
        mediaProjection = mediaProjectionManager?.getMediaProjection(Activity.RESULT_OK, data)
        if (mediaProjection == null) {
            Toast.makeText(this, "Failed to get media projection", Toast.LENGTH_SHORT).show()
            return
        }

        imageReader = ImageReader.newInstance(CAPTURE_WIDTH, CAPTURE_HEIGHT, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            image?.let {
                val detection = detector.detect(it)
                detection?.let { det ->
                    val centerX = det.centerX * CAPTURE_WIDTH
                    val centerY = det.centerY * CAPTURE_HEIGHT
                    val dx = centerX - CAPTURE_WIDTH / 2
                    val dy = centerY - CAPTURE_HEIGHT / 2
                    sendToESP32(dx.toInt(), dy.toInt())
                }
                it.close()
            }
        }, imageHandler)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ESP_Display",
            CAPTURE_WIDTH, CAPTURE_HEIGHT, 160,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null, null
        )

        if (virtualDisplay == null) {
            Toast.makeText(this, "Failed to create virtual display", Toast.LENGTH_SHORT).show()
            return
        }

        if (nativeStart()) {
            isRunning = true
            startButton.isEnabled = false
            stopButton.isEnabled = true
            statusText.text = "Running (YOLO + ESP)"
            Toast.makeText(this, "ESP started", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Failed to start native ESP", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopESP() {
        if (!isRunning) return
        isRunning = false
        nativeStop()

        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null

        startButton.isEnabled = true
        stopButton.isEnabled = false
        statusText.text = "Stopped"
        Toast.makeText(this, "ESP stopped", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        stopESP()
        nativeShutdown()
        super.onDestroy()
    }

    private fun sendToESP32(dx: Int, dy: Int) {
        // TODO: Gửi dx, dy qua BLE đến ESP32
        Log.d(TAG, "dx=$dx, dy=$dy")
    }

    init {
        System.loadLibrary("esp_native")
    }
}
