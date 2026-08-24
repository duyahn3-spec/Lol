package com.aimbuddy

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.Intent
import android.provider.Settings
import com.aimbuddy.capture.MediaProjectionManager
import com.aimbuddy.overlay.PoseOverlayView

class MainActivity : AppCompatActivity() {

    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var overlayView: PoseOverlayView
    private lateinit var projectionManager: MediaProjectionManager

    companion object {
        private const val REQUEST_CODE_OVERLAY = 1001
        private const val REQUEST_CODE_MEDIA_PROJECTION = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        overlayView = findViewById(R.id.overlayView)

        projectionManager = MediaProjectionManager(this, overlayView)

        btnStart.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(this)) {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    startActivityForResult(intent, REQUEST_CODE_OVERLAY)
                    return@setOnClickListener
                }
            }
            requestMediaProjection()
        }

        btnStop.setOnClickListener {
            projectionManager.stop()
            overlayView.clearPose()
            btnStart.isEnabled = true
            btnStop.isEnabled = false
            Toast.makeText(this, "Stopped", Toast.LENGTH_SHORT).show()
        }

        btnStop.isEnabled = false
    }

    private fun requestMediaProjection() {
        val intent = projectionManager.createScreenCaptureIntent()
        startActivityForResult(intent, REQUEST_CODE_MEDIA_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_CODE_OVERLAY -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                    requestMediaProjection()
                } else {
                    Toast.makeText(this, "Overlay permission required", Toast.LENGTH_SHORT).show()
                }
            }
            REQUEST_CODE_MEDIA_PROJECTION -> {
                if (resultCode == RESULT_OK && data != null) {
                    projectionManager.start(resultCode, data)
                    btnStart.isEnabled = false
                    btnStop.isEnabled = true
                    Toast.makeText(this, "Pose detection started", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "MediaProjection permission required", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
