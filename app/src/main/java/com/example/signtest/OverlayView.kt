package com.example.signtest

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/** วาดไหล่/ศอก/ข้อมือ + กรอบตัดมือ + จุดนิ้ว (ทุกค่าเป็นสัดส่วนของภาพ, PreviewView ใช้ FIT_CENTER) */
class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    class Hand(val pts: FloatArray, val box: FloatArray)

    @Volatile var frameW = 0
    @Volatile var frameH = 0
    @Volatile var hands: List<Hand> = emptyList()
    @Volatile var pose: FloatArray? = null     // LS,RS,LE,RE,LW,RW เป็น x,y (NaN = ไม่เห็น)

    private val boxPaint = Paint().apply { color = Color.GREEN; style = Paint.Style.STROKE; strokeWidth = 3f }
    private val linePaint = Paint().apply { color = Color.CYAN; strokeWidth = 4f }
    private val dotPaint = Paint().apply { color = Color.RED; style = Paint.Style.FILL }
    private val posePaint = Paint().apply { color = Color.YELLOW; strokeWidth = 5f; style = Paint.Style.FILL }

    private val conn = arrayOf(
        0 to 1, 1 to 2, 2 to 3, 3 to 4, 0 to 5, 5 to 6, 6 to 7, 7 to 8, 5 to 9, 9 to 10, 10 to 11, 11 to 12,
        9 to 13, 13 to 14, 14 to 15, 15 to 16, 13 to 17, 17 to 18, 18 to 19, 19 to 20, 0 to 17
    )

    override fun onDraw(c: Canvas) {
        val fw = frameW; val fh = frameH
        if (fw == 0 || fh == 0) return
        val s = min(width.toFloat() / fw, height.toFloat() / fh)
        val ox = (width - fw * s) / 2f; val oy = (height - fh * s) / 2f
        fun px(x: Float) = ox + (1-x) * fw * s
        fun py(y: Float) = oy + y * fh * s

        pose?.let { p ->
            // ไหล่-ไหล่, ไหล่-ศอก-ข้อมือ ทั้งสองข้าง
            val segs = arrayOf(0 to 1, 0 to 2, 2 to 4, 1 to 3, 3 to 5)
            for ((a, b) in segs) if (!p[2 * a].isNaN() && !p[2 * b].isNaN())
                c.drawLine(px(p[2 * a]), py(p[2 * a + 1]), px(p[2 * b]), py(p[2 * b + 1]), posePaint)
            for (i in 0 until 6) if (!p[2 * i].isNaN()) c.drawCircle(px(p[2 * i]), py(p[2 * i + 1]), 8f, posePaint)
        }
        for (h in hands) {
            c.drawRect(px(h.box[0]), py(h.box[1]), px(h.box[2]), py(h.box[3]), boxPaint)
            val p = h.pts
            for ((a, b) in conn) c.drawLine(px(p[2 * a]), py(p[2 * a + 1]), px(p[2 * b]), py(p[2 * b + 1]), linePaint)
            for (i in 0 until 21) c.drawCircle(px(p[2 * i]), py(p[2 * i + 1]), 6f, dotPaint)
        }
    }
}
