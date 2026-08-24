package com.aimbuddy.pose

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PoseDetector(context: Context) {

    private val interpreter: Interpreter
    private val inputSize = 256

    init {
        val modelBuffer = context.assets.open("pose_256_fp16.tflite")
            .use { it.readBytes() }
        val options = Interpreter.Options().apply {
            setNumThreads(4)
            // Bật GPU delegate (nếu có)
            // addDelegate(GpuDelegate())
        }
        interpreter = Interpreter(ByteBuffer.wrap(modelBuffer), options)
    }

    fun detect(bitmap: Bitmap): List<PoseKeypoint> {
        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val input = preprocess(resized)
        resized.recycle()

        val output = Array(1) { Array(32) { Array(32) { FloatArray(19) } } }
        interpreter.run(input, output)

        return PoseDecoder.decode(output[0])
    }

    private fun preprocess(bitmap: Bitmap): Array<Array<Array<FloatArray>>> {
        val input = Array(1) { Array(inputSize) { Array(inputSize) { FloatArray(3) } } }
        for (y in 0 until inputSize) {
            for (x in 0 until inputSize) {
                val pixel = bitmap.getPixel(x, y)
                input[0][y][x][0] = ((pixel shr 16 and 0xFF) - 128) / 256f
                input[0][y][x][1] = ((pixel shr 8 and 0xFF) - 128) / 256f
                input[0][y][x][2] = ((pixel and 0xFF) - 128) / 256f
            }
        }
        return input
    }
}
