package com.aimbuddy.pose

import kotlin.math.max

object PoseDecoder {

    // 18 keypoints: nose, neck, r-shoulder, r-elbow, r-wrist, l-shoulder, l-elbow, l-wrist,
    // r-hip, r-knee, r-ankle, l-hip, l-knee, l-ankle, r-eye, l-eye, r-ear, l-ear
    private val keypointNames = arrayOf(
        "Nose", "Neck", "RShoulder", "RElbow", "RWrist",
        "LShoulder", "LElbow", "LWrist", "RHip", "RKnee",
        "RAnkle", "LHip", "LKnee", "LAnkle", "REye",
        "LEye", "REar", "LEar"
    )

    fun decode(heatmaps: Array<Array<FloatArray>>): List<PoseKeypoint> {
        val keypoints = mutableListOf<PoseKeypoint>()
        val h = heatmaps.size
        val w = heatmaps[0].size

        for (k in 0 until 18) {
            var maxVal = -Float.MAX_VALUE
            var maxY = 0
            var maxX = 0
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val v = heatmaps[y][x][k]
                    if (v > maxVal) {
                        maxVal = v
                        maxY = y
                        maxX = x
                    }
                }
            }
            // Confidence > 0.3
            if (maxVal > 0.3f) {
                val px = (maxX + 0.5f) / w  // normalized 0..1
                val py = (maxY + 0.5f) / h
                keypoints.add(PoseKeypoint(keypointNames[k], px, py, maxVal))
            }
        }
        return keypoints
    }
}
