package com.example.signtest

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * @param points 21 landmarks as x0,y0,x1,y1,... (42 values), fractions (0..1) of the upright camera picture
 * @param score  how sure the model is that a hand is in the crop (0..1)
 * @param box    the square that was cut out, as left, top, right, bottom fractions (for the overlay)
 */
class HandResult(val points: FloatArray, val score: Float, val box: FloatArray)

/**
 * Finds the 21 finger/palm landmarks of one hand with Google's hand-landmark model (assets/hand_landmark.tflite)
 * run by plain TensorFlow Lite, so it works on Android 6. (MediaPipe Tasks itself needs Android 7+.)
 *
 * It has no palm detector. The pose model already knows where the wrist and elbow are, so the hand is cut out
 * just past the wrist, rotated so that the forearm points up (which is how the model was trained), and
 * the 21 points are mapped back into the camera picture.
 *
 * Model: input float32 [1,224,224,3] RGB 0..1; outputs: 21 landmarks (63 numbers, pixels of the 224 input),
 * hand presence, handedness, 21 world landmarks. Which output is which is worked out from the shapes and
 * names, because they differ a little between exports of the model. Not thread-safe.
 */
class HandLandmarker(context: Context) : AutoCloseable {

    private val interpreter: Interpreter
    private val input = ByteBuffer.allocateDirect(SIZE * SIZE * 3 * 4).order(ByteOrder.nativeOrder())
    private val outBuffers = HashMap<Int, Any>()
    private val outCounts = HashMap<Int, Int>()
    private val landmarkCandidates = ArrayList<Int>()
    private var landmarkOut = -1
    private var presenceOut = -1

    private val crop = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(crop)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pixels = IntArray(SIZE * SIZE)
    private val matrix = Matrix()
    private val inverse = Matrix()

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

        val inShape = interpreter.getInputTensor(0).shape()
        check(inShape.size == 4 && inShape[1] == SIZE && inShape[2] == SIZE && inShape[3] == 3) {
            "Unexpected hand model input ${inShape.joinToString("x")}, expected 1x${SIZE}x${SIZE}x3"
        }

        val scalars = ArrayList<Int>()
        for (i in 0 until interpreter.outputTensorCount) {
            val t = interpreter.getOutputTensor(i)
            val count = t.shape().fold(1) { a, b -> a * b }
            outBuffers[i] = ByteBuffer.allocateDirect(t.numBytes()).order(ByteOrder.nativeOrder())
            outCounts[i] = count
            Log.i(TAG, "hand model output $i: name=${t.name()} shape=${t.shape().joinToString("x")}")
            if (count == 63) landmarkCandidates.add(i)
            if (count == 1) scalars.add(i)
        }
        check(landmarkCandidates.isNotEmpty() && scalars.isNotEmpty()) {
            "This does not look like the MediaPipe hand-landmark model (expected 63-value and 1-value outputs)."
        }
        // Screen landmarks come first in the exports seen so far; confirmed by size on the first run.
        landmarkOut = landmarkCandidates.first()
        presenceOut = scalars.firstOrNull { interpreter.getOutputTensor(it).name().endsWith("_1") } ?: scalars.first()
    }

    /**
     * @param upright the upright camera picture
     * @param wx wrist x, wy wrist y, ex elbow x, ey elbow y: fractions of [upright]
     * @param shoulderPx shoulder width in pixels of [upright] (sets a minimum size for the crop)
     * @return the hand, or null if no hand was found
     */
    fun detect(upright: Bitmap, wx: Float, wy: Float, ex: Float, ey: Float, shoulderPx: Float): HandResult? {
        val bw = upright.width.toFloat()
        val bh = upright.height.toFloat()
        val wristX = wx * bw
        val wristY = wy * bh
        val vx = wristX - ex * bw
        val vy = wristY - ey * bh
        val forearm = hypot(vx, vy)
        val ux = if (forearm > 1f) vx / forearm else 0f
        val uy = if (forearm > 1f) vy / forearm else -1f

        // Square: about 1.2 forearms wide (never tiny when the arm points at the camera); the wrist sits at
        // roughly 72 % of its height, so the centre is 22 % of the side beyond the wrist.
        val side = max(1.2f * forearm, 0.6f * shoulderPx).coerceIn(48f, min(bw, bh) * 1.6f)
        val cx = wristX + ux * 0.22f * side
        val cy = wristY + uy * 0.22f * side
        val angle = Math.toDegrees(atan2(uy.toDouble(), ux.toDouble())).toFloat()

        matrix.reset()
        matrix.postTranslate(-cx, -cy)
        matrix.postRotate(-90f - angle)                   // forearm direction -> straight up
        matrix.postScale(SIZE / side, SIZE / side)
        matrix.postTranslate(SIZE / 2f, SIZE / 2f)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(upright, matrix, paint)

        crop.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        input.rewind()
        for (p in pixels) {
            input.putFloat(((p shr 16) and 0xFF) / 255f)
            input.putFloat(((p shr 8) and 0xFF) / 255f)
            input.putFloat((p and 0xFF) / 255f)
        }
        input.rewind()
        for (b in outBuffers.values) (b as ByteBuffer).rewind()
        interpreter.runForMultipleInputsOutputs(arrayOf<Any>(input), outBuffers)

        // Which of two 63-value outputs holds the screen landmarks: the one with the big (pixel) numbers.
        if (landmarkCandidates.size > 1) {
            landmarkOut = landmarkCandidates.maxByOrNull { maxAbs(read(it)) } ?: landmarkOut
        }
        var score = read(presenceOut)[0]
        if (score < 0f || score > 1f) score = 1f / (1f + exp(-score))     // a logit: squash it
        if (score < MIN_PRESENCE) return null

        val lm = read(landmarkOut)
        val toPixels = if (maxAbs(lm) <= 3f) SIZE.toFloat() else 1f       // some exports give 0..1
        val pts = FloatArray(42)
        for (i in 0 until 21) {
            pts[2 * i] = lm[3 * i] * toPixels
            pts[2 * i + 1] = lm[3 * i + 1] * toPixels
        }
        matrix.invert(inverse)
        inverse.mapPoints(pts)
        for (i in 0 until 21) {
            pts[2 * i] /= bw
            pts[2 * i + 1] /= bh
        }

        val corners = floatArrayOf(0f, 0f, SIZE.toFloat(), 0f, SIZE.toFloat(), SIZE.toFloat(), 0f, SIZE.toFloat())
        inverse.mapPoints(corners)
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (i in 0 until 4) {
            l = min(l, corners[2 * i]); r = max(r, corners[2 * i])
            t = min(t, corners[2 * i + 1]); b = max(b, corners[2 * i + 1])
        }
        val box = floatArrayOf(
            (l / bw).coerceIn(0f, 1f), (t / bh).coerceIn(0f, 1f),
            (r / bw).coerceIn(0f, 1f), (b / bh).coerceIn(0f, 1f)
        )
        return HandResult(pts, score, box)
    }

    private fun read(index: Int): FloatArray {
        val n = outCounts[index] ?: 0
        val buf = outBuffers[index] as ByteBuffer
        buf.rewind()
        val a = FloatArray(n)
        buf.asFloatBuffer().get(a)
        return a
    }

    private fun maxAbs(a: FloatArray): Float {
        var m = 0f
        for (v in a) m = max(m, kotlin.math.abs(v))
        return m
    }

    override fun close() {
        try { interpreter.close() } catch (_: Exception) {}
    }

    companion object {
        const val MODEL_ASSET = "hand_landmark.tflite"
        private const val TAG = "HandLandmarker"
        private const val SIZE = 224
        private const val MIN_PRESENCE = 0.5f
    }
}
