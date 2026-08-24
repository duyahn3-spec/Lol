package com.aimbuddy

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import java.nio.ByteBuffer
import org.tensorflow.lite.Interpreter

class TFLiteDetector(private val context: Context) {

    private lateinit var interpreter: Interpreter
    private val inputSize = 320

    init {
        loadModel()
    }

    private fun loadModel() {
        val modelBuffer = context.assets.open("models/yolov5n-det-int8-smart.tflite")
            .use { it.readBytes() }
        interpreter = Interpreter(ByteBuffer.wrap(modelBuffer))
    }

    fun detect(image: Image): Detection? {
        val bitmap = imageToBitmap(image) ?: return null
        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val input = preprocess(resized)

        val output = Array(1) { Array(25200) { FloatArray(85) } }
        interpreter.run(input, output)

        val detections = parseOutput(output[0])
        val person = detections.firstOrNull { it.classId == 0 }

        bitmap.recycle()
        resized.recycle()
        return person
    }

    private fun preprocess(bitmap: Bitmap): Array<Array<Array<FloatArray>>> {
        val input = Array(1) { Array(inputSize) { Array(inputSize) { FloatArray(3) } } }
        for (y in 0 until inputSize) {
            for (x in 0 until inputSize) {
                val pixel = bitmap.getPixel(x, y)
                input[0][y][x][0] = ((pixel shr 16 and 0xFF) / 255f)
                input[0][y][x][1] = ((pixel shr 8 and 0xFF) / 255f)
                input[0][y][x][2] = ((pixel and 0xFF) / 255f)
            }
        }
        return input
    }

    private fun parseOutput(output: Array<FloatArray>): List<Detection> {
        val detections = mutableListOf<Detection>()
        for (i in output.indices) {
            val confidence = output[i][4]
            if (confidence < 0.4f) continue
            val classId = output[i].indices.maxByOrNull { output[i][it] } ?: continue
            if (classId != 0) continue
            val x = output[i][0]
            val y = output[i][1]
            val w = output[i][2]
            val h = output[i][3]
            detections.add(Detection(x, y, w, h, confidence))
        }
        return detections
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val buffer = image.planes[0].buffer
        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }
}

data class Detection(val x: Float, val y: Float, val w: Float, val h: Float, val confidence: Float) {
    val centerX get() = x + w / 2
    val centerY get() = y + h / 2
}
