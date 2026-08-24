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
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ESP_MainActivity"
        private const val REQUEST_MEDIA_PROJECTION = 1001
        private const val REQUEST_OVERLAY_PERMISSION = 1002

        // Reduced resolution for T606 (4GB RAM)
        private const val CAPTURE_WIDTH = 320
        private const val CAPTURE_HEIGHT = 240
        private const val CAPTURE_FPS = 15

        private const val PREFS_NAME = "aimbuddy_prefs"
        private const val ASSET_MODEL_PARAM = "models/yolo26n-opt.param"
        private const val ASSET_MODEL_BIN = "models/yolo26n-opt.bin"

        init {
            System.loadLibrary("esp_native")
        }
    }

    // UI state
    private var isRunning = false
    private var statusText = "Status: Idle"

    // Overlay components
    private var imguiOverlay: ImGuiGLSurface? = null
    private var windowManager: WindowManager? = null
    private var isOverlayVisible = false
    private val isStopping = AtomicBoolean(false)
    private val isStarting = AtomicBoolean(false)

    // MediaProjection components
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var projectionCallbackRegistered = false
    private val mediaProjectionCallback = object : Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection stopped by system/user")
            runOnUiThread {
                if (isRunning) {
                    Toast.makeText(this@MainActivity, "Screen capture ended. ESP stopped.", Toast.LENGTH_LONG).show()
                    stopESP()
                }
            }
        }
    }
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val imageThread = HandlerThread("esp-image-reader").also { it.start() }
    private val imageHandler = Handler(imageThread.looper)

    // Display metrics
    private var screenWidth = 1080
    private var screenHeight = 2400
    private var screenDensity = 1

    // Rendering
    private val renderHandler = Handler(Looper.getMainLooper())

    // Native methods (only ESP overlay, no aim)
    private external fun nativeInit(assetManager: android.content.res.AssetManager,
                                    screenWidth: Int, screenHeight: Int): Boolean
    private external fun nativeStart()
    private external fun nativeStop()
    private external fun nativeShutdown()
    private external fun nativeIsRunning(): Boolean
    private external fun nativeSetModelPaths(paramPath: String?, binPath: String?)

    private lateinit var modelCatalog: ModelCatalog

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        
        // Simple UI (no Compose)
        setContentView(R.layout.activity_main)
        
        enableImmersiveMode()

        Log.i(TAG, "onCreate")

        val displayMetrics = DisplayMetrics()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager?.defaultDisplay?.getRealMetrics(displayMetrics)
        
        if (displayMetrics.widthPixels < displayMetrics.heightPixels) {
            screenWidth = displayMetrics.heightPixels
            screenHeight = displayMetrics.widthPixels
        } else {
            screenWidth = displayMetrics.widthPixels
            screenHeight = displayMetrics.heightPixels
        }
        screenDensity = displayMetrics.densityDpi

        Log.i(TAG, "Screen: ${screenWidth}x${screenHeight}, density: $screenDensity")

        modelCatalog = ModelCatalog(this)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager

        if (!nativeInit(assets, screenWidth, screenHeight)) {
            Log.e(TAG, "Failed to initialize native components")
            Toast.makeText(this, "Failed to initialize ESP. Check model files.", Toast.LENGTH_LONG).show()
            statusText = "Status: Init Failed"
        } else {
            statusText = "Status: Ready"
        }

        // Setup buttons
        findViewById<android.widget.Button>(R.id.startButton).setOnClickListener {
            onStartClicked()
        }
        findViewById<android.widget.Button>(R.id.stopButton).setOnClickListener {
            onStopClicked()
        }

        // Update status text
        findViewById<android.widget.TextView>(R.id.statusText).text = statusText
    }

    private fun onStartClicked() {
        if (isRunning || isStarting.get()) return
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"))
                startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
                return
            }
        }
        requestMediaProjectionPermission()
    }

    private fun requestMediaProjectionPermission() {
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
                    requestMediaProjectionPermission()
                } else {
                    Toast.makeText(this, "Overlay permission required.", Toast.LENGTH_SHORT).show()
                }
            }
            REQUEST_MEDIA_PROJECTION -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    startESP(data)
                } else {
                    Toast.makeText(this, "MediaProjection permission required.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startESP(data: Intent) {
        if (isStarting.get() || isRunning) return
        
        isStarting.set(true)
        statusText = "Status: Starting..."
        findViewById<android.widget.TextView>(R.id.statusText).text = statusText

        mediaProjection = mediaProjectionManager?.getMediaProjection(Activity.RESULT_OK, data)
        if (mediaProjection == null) {
            Toast.makeText(this, "Failed to start screen capture", Toast.LENGTH_SHORT).show()
            isStarting.set(false)
            statusText = "Status: Failed"
            findViewById<android.widget.TextView>(R.id.statusText).text = statusText
            return
        }

        // Setup ImageReader
        imageReader = ImageReader.newInstance(CAPTURE_WIDTH, CAPTURE_HEIGHT, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                // Image is passed to native code via JNI for ESP rendering
                // No touch injection, only ESP overlay
                image.close()
            }
        }, imageHandler)

        // Create VirtualDisplay
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ESP_Display",
            CAPTURE_WIDTH, CAPTURE_HEIGHT, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null, null
        )

        if (virtualDisplay == null) {
            Toast.makeText(this, "Failed to create virtual display", Toast.LENGTH_SHORT).show()
            stopESP()
            return
        }

        // Start native ESP overlay (no aim)
        if (nativeStart()) {
            isRunning = true
            statusText = "Status: Running (ESP Only)"
            findViewById<android.widget.TextView>(R.id.statusText).text = statusText
            Toast.makeText(this, "ESP started (Visual Assist only)", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Failed to start ESP engine", Toast.LENGTH_SHORT).show()
            stopESP()
        }
        
        isStarting.set(false)
    }

    private fun stopESP() {
        if (!isRunning && !isStarting.get()) return
        
        isRunning = false
        isStarting.set(false)
        statusText = "Status: Stopping..."
        findViewById<android.widget.TextView>(R.id.statusText).text = statusText

        nativeStop()
        
        virtualDisplay?.release()
        virtualDisplay = null
        
        imageReader?.close()
        imageReader = null
        
        mediaProjection?.stop()
        mediaProjection = null
        
        statusText = "Status: Stopped"
        findViewById<android.widget.TextView>(R.id.statusText).text = statusText
        Log.i(TAG, "ESP stopped")
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        stopESP()
        nativeShutdown()
        super.onDestroy()
    }
}