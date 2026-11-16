package com.example.signtest

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * MoveNet single-pose (Lightning/Thunder) .tflite: อินพุต [1,H,W,3] RGB 0..255 (int32/uint8/float32),
 * เอาต์พุต [1,1,17,3] = (y, x, score) เป็นสัดส่วน 0..1
 * หาไฟล์ใน assets เองโดยเลือกไฟล์ .tflite ที่ชื่อมี "movenet" หรือ "pose"
 * ภาพถูกยืดเป็นสี่เหลี่ยมจัตุรัสตรง ๆ (ไม่เติมขอบ) ค่าที่ได้จึงเป็นสัดส่วนของภาพเดิมโดยตรง
 */
class PoseModel(ctx: Context) : AutoCloseable {

    class Out(val x: FloatArray, val y: FloatArray, val score: FloatArray)

    private val interp: Interpreter
    private val h: Int
    private val w: Int
    private val type: DataType
    private val buf: ByteBuffer
    private val pixels: IntArray
    private val out = Array(1) { Array(1) { Array(17) { FloatArray(3) } } }
    val assetName: String

    init {
        val names = ctx.assets.list("")?.toList() ?: emptyList()
        assetName = names.firstOrNull { it.endsWith(".tflite") && (it.contains("movenet", true) || it.contains("pose", true)) }
            ?: error("ไม่พบโมเดลท่าทาง (MoveNet) ใน app/src/main/assets — คัดลอกไฟล์ .tflite ของ MoveNet จากแอป IAQ มาวาง (ชื่อควรมี movenet หรือ pose)")
        val afd = ctx.assets.openFd(assetName)
        val mapped = FileInputStream(afd.fileDescriptor).channel
            .map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
        interp = Interpreter(mapped, Interpreter.Options().setNumThreads(2))
        val t = interp.getInputTensor(0)
        val s = t.shape()
        h = s[1]; w = s[2]; type = t.dataType()
        Log.i("PoseModel", "$assetName in=${s.joinToString("x")} type=$type out=${interp.getOutputTensor(0).shape().joinToString("x")}")
        val bytes = when (type) { DataType.UINT8 -> 1; else -> 4 }
        buf = ByteBuffer.allocateDirect(h * w * 3 * bytes).order(ByteOrder.nativeOrder())
        pixels = IntArray(h * w)
    }

    fun detect(upright: Bitmap): Out {
        val scaled = Bitmap.createScaledBitmap(upright, w, h, true)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled !== upright) scaled.recycle()
        buf.rewind()
        for (p in pixels) {
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            when (type) {
                DataType.UINT8 -> { buf.put(r.toByte()); buf.put(g.toByte()); buf.put(b.toByte()) }
                DataType.INT32 -> { buf.putInt(r); buf.putInt(g); buf.putInt(b) }
                else -> { buf.putFloat(r.toFloat()); buf.putFloat(g.toFloat()); buf.putFloat(b.toFloat()) }
            }
        }
        buf.rewind()
        interp.run(buf, out)
        val kp = out[0][0]
        return Out(FloatArray(17) { kp[it][1] }, FloatArray(17) { kp[it][0] }, FloatArray(17) { kp[it][2] })
    }

    override fun close() { try { interp.close() } catch (_: Exception) {} }

    companion object {
        // ดัชนี keypoint มาตรฐาน COCO ของ MoveNet
        const val L_SHOULDER = 5; const val R_SHOULDER = 6
        const val L_ELBOW = 7; const val R_ELBOW = 8
        const val L_WRIST = 9; const val R_WRIST = 10
    }
}

class SignPrediction(val label: String, val confidence: Float)
