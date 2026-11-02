package com.leslie.tot.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** The 21 hand points as fractions (0..1) of the upright picture: x0,y0,x1,y1,... and how sure the model is. */
class HandPoints(val xy: FloatArray, val presence: Float)

/**
 * Google's MediaPipe hand landmark model (assets/hand_landmark.tflite) run with plain TensorFlow Lite,
 * so it works on Android 6 (MediaPipe Tasks itself needs Android 7+).
 *
 * Model: input float32 [1,224,224,3] RGB 0..1 (a picture of ONE hand, fingers pointing up);
 * outputs "Identity" = 21 x (x, y, z) in pixels of that 224 x 224 picture, "Identity_1" = hand presence 0..1.
 * The finder of the hand (MediaPipe's palm detector) is not used: the square is placed from the forearm
 * (elbow -> wrist) that MoveNet finds, and turned so that the forearm points up.
 * Not thread-safe.
 */
class HandLandmarkExtractor(context: Context) : AutoCloseable {

    private val interpreter: Interpreter
    private val landmarkOut: Int
    private val presenceOut: Int
    private val input = ByteBuffer.allocateDirect(SIZE * SIZE * 4 * 3).order(ByteOrder.nativeOrder())
    private val pixels = IntArray(SIZE * SIZE)
    private val crop = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
    private val cropCanvas = Canvas(crop)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val landmarks = Array(1) { FloatArray(63) }
    private val presence = Array(1) { FloatArray(1) }

    init {
        val options = Interpreter.Options().setNumThreads(2)
        interpreter = try {
            val afd = context.assets.openFd(MODEL_ASSET)
            val mapped = FileInputStream(afd.fileDescriptor).channel
                .map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            Interpreter(mapped, options)
        } catch (e: java.io.IOException) {
            val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
            val direct = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
            direct.put(bytes).rewind()
            Interpreter(direct, options)
        }
        val shape = interpreter.getInputTensor(0).shape()
        check(shape.size == 4 && shape[1] == SIZE && shape[2] == SIZE && shape[3] == 3) {
            "Unexpected hand model input ${shape.joinToString("x")}, expected 1x224x224x3"
        }
        // Find the outputs by name (their order in the file is not guaranteed to be the same everywhere).
        var lm = -1
        var pr = -1
        for (i in 0 until interpreter.outputTensorCount) {
            val t = interpreter.getOutputTensor(i)
            val n = t.numElements()
            if (n == 63 && t.name() == "Identity") lm = i
            if (n == 1 && t.name() == "Identity_1") pr = i
        }
        if (lm < 0) lm = (0 until interpreter.outputTensorCount).first { interpreter.getOutputTensor(it).numElements() == 63 }
        if (pr < 0) pr = (0 until interpreter.outputTensorCount).first { interpreter.getOutputTensor(it).numElements() == 1 }
        landmarkOut = lm
        presenceOut = pr
    }

    /**
     * @param upright the camera picture, rotated upright
     * @param cx,cy centre of the square, in pixels of [upright]
     * @param side side of the square, in pixels of [upright]
     * @param angleDeg rotation (degrees, clockwise on the screen) that makes the hand point up
     * @return the points, or null when the model does not see a hand
     */
    fun detect(upright: Bitmap, cx: Float, cy: Float, side: Float, angleDeg: Float): HandPoints? {
        val m = Matrix().apply {
            postTranslate(-cx, -cy)
            postRotate(angleDeg)
            postScale(SIZE / side, SIZE / side)
            postTranslate(SIZE / 2f, SIZE / 2f)
        }
        cropCanvas.drawColor(Color.BLACK)
        cropCanvas.drawBitmap(upright, m, paint)

        crop.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        input.rewind()
        for (p in pixels) {
            input.putFloat(((p shr 16) and 0xFF) / 255f)
            input.putFloat(((p shr 8) and 0xFF) / 255f)
            input.putFloat((p and 0xFF) / 255f)
        }
        input.rewind()

        val outputs = HashMap<Int, Any>()
        outputs[landmarkOut] = landmarks
        outputs[presenceOut] = presence
        interpreter.runForMultipleInputsOutputs(arrayOf<Any>(input), outputs)

        var score = presence[0][0]
        if (score < 0f || score > 1f) score = 1f / (1f + kotlin.math.exp(-score))   // logit -> probability

        // pixels of the 224 picture -> pixels of the upright picture -> fractions
        val pts = FloatArray(42)
        for (i in 0 until 21) {
            pts[2 * i] = landmarks[0][3 * i]
            pts[2 * i + 1] = landmarks[0][3 * i + 1]
        }
        val inv = Matrix()
        if (!m.invert(inv)) return null
        inv.mapPoints(pts)
        val w = upright.width.toFloat()
        val h = upright.height.toFloat()
        for (i in 0 until 21) {
            pts[2 * i] /= w
            pts[2 * i + 1] /= h
        }
        return HandPoints(pts, score)
    }

    override fun close() {
        try { interpreter.close() } catch (_: Exception) {}
    }

    companion object {
        const val MODEL_ASSET = "hand_landmark.tflite"
        private const val SIZE = 224
    }
}
