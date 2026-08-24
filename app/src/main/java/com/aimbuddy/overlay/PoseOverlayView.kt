package com.aimbuddy.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.aimbuddy.pose.PoseKeypoint

class PoseOverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var keypoints: List<PoseKeypoint> = emptyList()
    private val paint = Paint().apply {
        color = Color.GREEN
        strokeWidth = 8f
        style = Paint.Style.STROKE
    }
    private val dotPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
    }

    fun updatePose(points: List<PoseKeypoint>) {
        keypoints = points
        invalidate()
    }

    fun clearPose() {
        keypoints = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (keypoints.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()

        // Vẽ các điểm (keypoints)
        for (kp in keypoints) {
            if (kp.confidence > 0.3f) {
                val x = kp.x * w
                val y = kp.y * h
                canvas.drawCircle(x, y, 12f, dotPaint)
                canvas.drawText(kp.name, x + 10, y - 10, paint)
            }
        }

        // Vẽ khung xương (skeleton) - nối các keypoints
        val connections = listOf(
            0 to 1,   // nose - neck
            1 to 2, 1 to 5, // neck - shoulders
            2 to 3, 3 to 4, // r-arm
            5 to 6, 6 to 7, // l-arm
            1 to 8, 1 to 11, // neck - hips
            8 to 9, 9 to 10, // r-leg
            11 to 12, 12 to 13 // l-leg
        )

        for ((i, j) in connections) {
            if (i < keypoints.size && j < keypoints.size) {
                val p1 = keypoints[i]
                val p2 = keypoints[j]
                if (p1.confidence > 0.3f && p2.confidence > 0.3f) {
                    canvas.drawLine(
                        p1.x * w, p1.y * h,
                        p2.x * w, p2.y * h,
                        paint
                    )
                }
            }
        }
    }
}
