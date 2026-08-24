package com.aimbuddy.pose

data class PoseKeypoint(
    val name: String,
    val x: Float, // normalized 0..1
    val y: Float,
    val confidence: Float
)
