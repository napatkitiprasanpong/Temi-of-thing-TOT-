package com.example.signtest

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.leslie.tot.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.max
import androidx.camera.core.resolutionselector.AspectRatioStrategy

/**
 * ทดสอบ pipeline เดียวกับแอป IAQ (WordSignRecognizer):
 * MoveNet -> ตัดมือตามข้อมือ/ศอก -> hand_landmark -> เก็บเป็นคลิปจนมือลง -> SignFeatures.build (32x98) -> sign_words
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val TAG = "SignTest"
        const val MIN_POSE_SCORE = 0.3f
        const val MIN_SEGMENT_MS = 400L
        const val MIN_ACTIVE_FRAMES = 5
        const val ACCEPT_CONFIDENCE = 0.60f
        const val ACCEPT_MARGIN = 0.15f
        const val MAX_SIDE_PX = 960
        const val HISTORY = 6
    }

    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var tvResult: TextView
    private lateinit var tvTop3: TextView
    private lateinit var tvStats: TextView

    private var pose: PoseModel? = null
    private var hand: HandLandmarker? = null
    private var clf: WordSignClassifier? = null
    private lateinit var exec: ExecutorService
    private val seg = Segmenter()

    private var useFront = true
    private var fps = 0f
    private var lastTs = 0L
    private var aspect = 0.75f
    private val history = ArrayList<String>()
    private var lastResult = "วางตัวให้เห็นไหล่ทั้งสองข้างและมือ แล้วทำท่าทีละคำ จากนั้นลดมือลง"
    private var lastTop = ""

    // เตรียมบิตแมป (ใช้ซ้ำ)
    private var prep: Bitmap? = null
    private var prepCanvas: Canvas? = null
    private val prepPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.previewView)
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        overlay = findViewById(R.id.overlay)
        tvResult = findViewById(R.id.tvResult)
        tvTop3 = findViewById(R.id.tvTop3)
        tvStats = findViewById(R.id.tvStats)
        exec = Executors.newSingleThreadExecutor()

        try {
            clf = WordSignClassifier(this)
            hand = HandLandmarker(this)
            pose = PoseModel(this)
            tvStats.text = "โหลดโมเดลแล้ว: ${pose!!.assetName}, hand_landmark, sign_words (${clf!!.labels.size} คำ)"
        } catch (t: Throwable) {
            Log.e(TAG, "load failed", t)
            tvResult.text = "โหลดโมเดลไม่ได้"
            tvTop3.text = t.message ?: t.javaClass.simpleName
        }

        findViewById<Button>(R.id.btnSwitch).setOnClickListener { useFront = !useFront; seg.reset(); bindCamera() }
        findViewById<Button>(R.id.btnClear).setOnClickListener { seg.reset(); history.clear(); lastTop = ""; tvTop3.text = ""; tvResult.text = "ล้างแล้ว" }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) bindCamera()
        else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) bindCamera()
        else Toast.makeText(this, "ต้องอนุญาตกล้อง", Toast.LENGTH_LONG).show()
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).build()
                .also {
                    val previewView = findViewById<androidx.camera.view.PreviewView>(R.id.previewView)
                    it.setSurfaceProvider(previewView.surfaceProvider) }
            val rs = ResolutionSelector.Builder()
                .setAspectRatioStrategy(
                    AspectRatioStrategy(
                        AspectRatio.RATIO_4_3,
                        AspectRatioStrategy.FALLBACK_RULE_AUTO
                    )
                )
                .build()
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(rs)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build().also { it.setAnalyzer(exec) { proxy -> analyze(proxy) } }
            val sel = if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, sel, preview, analysis)
            } catch (e: Exception) {
                Toast.makeText(this, "เปิดกล้องไม่ได้: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(proxy: ImageProxy) {
        try {
            val pm = pose; val hm = hand; val cl = clf
            if (pm == null || hm == null || cl == null) return
            val now = SystemClock.elapsedRealtime()
            val t0 = System.nanoTime()

            val upright = upright(proxy.toBitmap(), proxy.imageInfo.rotationDegrees)
            aspect = upright.width.toFloat() / upright.height

            val p = pm.detect(upright)
            val t1 = System.nanoTime()

            val order = intArrayOf(PoseModel.L_SHOULDER, PoseModel.R_SHOULDER, PoseModel.L_ELBOW,
                PoseModel.R_ELBOW, PoseModel.L_WRIST, PoseModel.R_WRIST)
            val pose12 = FloatArray(12) { Float.NaN }
            for ((i, idx) in order.withIndex()) if (p.score[idx] >= MIN_POSE_SCORE) { pose12[2 * i] = p.x[idx]; pose12[2 * i + 1] = p.y[idx] }

            var left: FloatArray? = null; var right: FloatArray? = null
            val shown = ArrayList<OverlayView.Hand>()
            val ls = 0; val rs = 2; val le = 4; val re = 6; val lw = 8; val rw = 10
            val shouldersSeen = !pose12[ls].isNaN() && !pose12[ls + 1].isNaN() && !pose12[rs].isNaN() && !pose12[rs + 1].isNaN()
            if (shouldersSeen) {
                val shoulderY = (pose12[ls + 1] + pose12[rs + 1]) / 2f
                val shoulderW = hypot((pose12[rs] - pose12[ls]) * aspect, pose12[rs + 1] - pose12[ls + 1])
                if (shoulderW >= 0.02f) {
                    val shoulderPx = shoulderW * upright.height
                    for (side in 0..1) {
                        val w = if (side == 0) lw else rw; val e = if (side == 0) le else re
                        val wx = pose12[w]; val wy = pose12[w + 1]; val ex = pose12[e]; val ey = pose12[e + 1]
                        if (wx.isNaN() || wy.isNaN() || ex.isNaN() || ey.isNaN()) continue
                        if ((wy - shoulderY) / shoulderW >= SignFeatures.ACTIVE_Y_MAX) continue   // มือลงอยู่
                        val h = hm.detect(upright, wx, wy, ex, ey, shoulderPx) ?: continue
                        if (side == 0) left = h.points else right = h.points
                        shown.add(OverlayView.Hand(h.points, h.box))
                    }
                }
            }
            val t2 = System.nanoTime()

            val frame = SignFrame(left, right, pose12)
            val clip = seg.onFrame(now, frame)
            val finished = clip?.let { finish(it, cl) }

            overlay.frameW = upright.width; overlay.frameH = upright.height
            overlay.hands = shown; overlay.pose = pose12

            val nowNs = System.nanoTime()
            if (lastTs != 0L) fps = 0.9f * fps + 0.1f * (1e9f / (nowNs - lastTs))
            lastTs = nowNs
            val stats = "fps %.1f | pose %.0fms | hand %.0fms | shoulders:%s hands:%d".format(
                fps, (t1 - t0) / 1e6, (t2 - t1) / 1e6, if (shouldersSeen) "ok" else "NO", shown.size)
            val signing = seg.inSign

            runOnUiThread {
                overlay.invalidate()
                tvStats.text = stats
                if (finished != null) { lastResult = finished.first; lastTop = finished.second }
                tvResult.text = if (signing) "กำลังทำท่า... ${seg.activeFrames} เฟรม" else lastResult
                tvTop3.text = if (signing) "" else lastTop
            }
        } catch (t: Throwable) {
            Log.e(TAG, "analyze", t)
        } finally {
            proxy.close()
        }
    }

    /** ตัดสินคลิปหนึ่งคลิป -> (ข้อความหลัก, top3) */
    private fun finish(c: Segmenter.Clip, cl: WordSignClassifier): Pair<String, String> {
        if (c.activeFrames < MIN_ACTIVE_FRAMES || c.durationMs < MIN_SEGMENT_MS)
            return "สั้นเกินไป ลองทำช้าลงอีกนิด" to ""
        val feats = SignFeatures.build(c.frames, aspect)
            ?: return "เห็นไหล่/มือไม่ชัด ลองถอยออกมาหน่อย" to ""
        val t = System.nanoTime()
        val guesses = cl.classify(feats)
        val ms = (System.nanoTime() - t) / 1e6
        val best = guesses[0]; val second = guesses.getOrNull(1)?.confidence ?: 0f
        val sure = best.confidence >= ACCEPT_CONFIDENCE && best.confidence - second >= ACCEPT_MARGIN
        val list = guesses.joinToString("\n") { "%-14s %5.1f%%".format(it.label, it.confidence * 100) } +
            "\n(%d เฟรม, %.1fs, sign_words %.0fms)".format(c.frames.size, c.durationMs / 1000f, ms)
        Log.i(TAG, "clip frames=${c.frames.size} active=${c.activeFrames} -> ${guesses.joinToString { "${it.label}:${it.confidence}" }}")
        return if (sure) {
            history.add(best.label); while (history.size > HISTORY) history.removeAt(0)
            ("✔ ${best.label}   [" + history.joinToString(" / ") + "]") to list
        } else "ไม่แน่ใจ (อาจเป็น ${best.label}) ลองทำอีกครั้ง" to list
    }

    /** หมุนภาพให้ตั้งตรง (กลับซ้ายขวาถ้าเป็นกล้องหน้า ให้ตรงกับพรีวิว) แล้วย่อ */
    private fun upright(src: Bitmap, rotation: Int): Bitmap {
        val sideways = rotation % 180 != 0
        val uw = if (sideways) src.height else src.width
        val uh = if (sideways) src.width else src.height
        val scale = minOf(1f, MAX_SIDE_PX.toFloat() / max(uw, uh))
        val dw = max(1, (uw * scale).toInt()); val dh = max(1, (uh * scale).toInt())
        var bmp = prep
        if (bmp == null || bmp.width != dw || bmp.height != dh) {
            bmp = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888); prep = bmp; prepCanvas = Canvas(bmp)
        }
        val m = Matrix().apply {
            postTranslate(-src.width / 2f, -src.height / 2f)
            postRotate(rotation.toFloat())
            postScale(scale, scale)
            postTranslate(dw / 2f, dh / 2f)
        }
        prepCanvas!!.drawColor(Color.BLACK)
        prepCanvas!!.drawBitmap(src, m, prepPaint)
        return bmp
    }

    override fun onDestroy() {
        super.onDestroy()
        exec.shutdown()
        pose?.close(); hand?.close(); clf?.close()
    }
}
