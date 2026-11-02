package com.leslie.tot.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Recognises whole signed WORDS (ASL, ~35 words such as hello / thank you / I am hungry) and has temi say them.
 * Works on Android 6: no MediaPipe, only MoveNet (ONNX Runtime) and two TensorFlow Lite models.
 *
 * Every camera frame:
 *  1. MoveNet finds shoulders, elbows and wrists.
 *  2. For each raised wrist a square is cut out along the forearm, turned upright, and
 *     assets/hand_landmark.tflite returns the 21 hand points. One frame = both hands + 6 body points.
 *  3. A sign starts when a wrist is raised and ends when both hands have been down for [END_GAP_MS].
 *  4. The frames of the sign go through [SignFeatures] (same maths as the training code) to 32 x 98 numbers
 *     and assets/sign_words.tflite names the word. If it is sure enough, temi says it.
 *
 * Shares [SignState] / [SignOverlay] with [SignRecognizer] (letters), so the same screen shows both.
 * Only runs while the Hand Reading screen is on.
 */
object SignWordRecognizer {

    // ---- tunables ----
    /** The word is accepted when the model is at least this sure. */
    private const val ACCEPT_THRESHOLD = 0.55f
    /** Both hands down for this long: the sign is finished and is recognised. */
    private const val END_GAP_MS = 700L
    /** A sign longer than this is cut and recognised anyway. */
    private const val MAX_SIGN_MS = 6000L
    /** A raised hand shorter than this is not a sign (a wave of the hand away, scratching the nose ...). */
    private const val MIN_SIGN_MS = 350L
    private const val MIN_FRAME_GAP_MS = 40L
    private const val MAX_FRAMES = 200
    /** The hand model only runs for a wrist above this many shoulder-widths below the shoulders (saves battery). */
    private const val RUN_Y_MAX = 2.2
    private const val BODY_SCORE = 0.3f
    private const val JOINT_SCORE = 0.2f
    private const val HAND_PRESENCE = 0.5f
    private const val MAX_SIDE_PX = 480
    private const val HISTORY_SIZE = 6
    private const val READY_MESSAGE = "Raise a hand and sign a word. Lower your hands when you are done."

    private val lock = Any()
    private val modelLock = Any()

    private val _state = MutableStateFlow(SignState())
    val state: StateFlow<SignState> = _state.asStateFlow()

    /** temi says each recognised word aloud. */
    val speakEnabled = MutableStateFlow(true)

    /**
     * Mirror the signer (swap left and right hand, negate x). Switch on when the words are not recognised but the
     * signs look right: it means the training videos and the camera disagree about which hand is "left".
     */
    val mirror = MutableStateFlow(false)

    @Volatile private var detector: PoseDetector? = null
    @Volatile private var handModel: HandLandmarkExtractor? = null
    @Volatile private var classifier: SignWordClassifier? = null
    private var session = 0
    private val loading = AtomicBoolean(false)
    @Volatile private var wanted = false

    // frame preparation (analysis thread only)
    private var prep: Bitmap? = null
    private var prepCanvas: Canvas? = null
    private val prepPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var lastRunMs = 0L
    private var fpsCount = 0
    private var fpsSince = 0L

    // sign collection (analysis thread only)
    private val frames = ArrayList<DoubleArray>()
    private var inSign = false
    private var signStartMs = 0L
    private var lastActiveMs = 0L
    private var lastAspect = 0.75
    private var notice = ""
    private var lastGuesses: List<SignPrediction> = emptyList()
    private var lastConfidence = 0f

    private class FrameResult(val raw: DoubleArray, val overlay: SignOverlay?)

    // ---------------------------------------------------------------------------------------------
    // Loading
    // ---------------------------------------------------------------------------------------------

    /** Loads the models. Call from a background thread when the screen opens. */
    fun prepare(context: Context) {
        wanted = true
        if (!loading.compareAndSet(false, true)) return
        var stale = false
        val mySession = synchronized(lock) { if (detector != null) -1 else session }
        if (mySession < 0) { loading.set(false); return }
        try {
            val present = context.assets.list("")?.toSet() ?: emptySet()
            val missing = listOf(
                SignWordClassifier.MODEL_ASSET, SignWordClassifier.LABELS_ASSET,
                HandLandmarkExtractor.MODEL_ASSET, PoseDetector.MODEL_ASSET
            ).filter { it !in present }
            if (missing.isNotEmpty()) {
                fail("Missing file in app/src/main/assets: ${missing.joinToString(", ")}.")
                return
            }
            _state.value = SignState(status = SignStatus.LOADING, message = "Loading models...")

            val (cls, hands, det) = loadModels(context)
            synchronized(lock) {
                if (session != mySession) {
                    det.close(); hands.close(); cls.close()
                    stale = true
                    return@synchronized
                }
                classifier = cls
                handModel = hands
                detector = det
            }
            if (!stale) {
                resetSigning()
                _state.value = SignState(status = SignStatus.READY, message = READY_MESSAGE)
            }
        } catch (t: Throwable) {
            fail("Could not load the models: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            loading.set(false)
        }
        if (stale && wanted) prepare(context)
    }

    private fun loadModels(context: Context): Triple<SignWordClassifier, HandLandmarkExtractor, PoseDetector> {
        val cls = SignWordClassifier(context)
        val hands = try { HandLandmarkExtractor(context) } catch (t: Throwable) { cls.close(); throw t }
        val det = try { PoseDetector(context.applicationContext) } catch (t: Throwable) { cls.close(); hands.close(); throw t }
        return Triple(cls, hands, det)
    }

    private fun fail(message: String) {
        _state.value = SignState(status = SignStatus.ERROR, message = message, error = message)
    }

    /** Frees the models and resets. Call when the screen closes or switches to letters. */
    fun stop() {
        wanted = false
        val models = synchronized(lock) {
            session++
            val t = Triple(detector, handModel, classifier)
            detector = null; handModel = null; classifier = null
            t
        }
        // Closing waits for a frame that is still being analysed, so keep it off the UI thread.
        Thread {
            synchronized(modelLock) {
                try { models.first?.close() } catch (_: Exception) {}
                try { models.second?.close() } catch (_: Exception) {}
                try { models.third?.close() } catch (_: Exception) {}
                resetSigning()
            }
        }.start()
        _state.value = SignState()
    }

    private fun resetSigning() {
        frames.clear()
        inSign = false
        notice = ""
        lastGuesses = emptyList()
        lastConfidence = 0f
    }

    // ---------------------------------------------------------------------------------------------
    // Camera frames
    // ---------------------------------------------------------------------------------------------

    fun createAnalyzer(): ImageAnalysis.Analyzer = ImageAnalysis.Analyzer { image -> analyze(image) }

    private fun analyze(image: ImageProxy) {
        try {
            val det = detector ?: return
            val hands = handModel ?: return
            val cls = classifier ?: return

            val now = SystemClock.elapsedRealtime()
            if (now - lastRunMs < MIN_FRAME_GAP_MS) return
            lastRunMs = now

            val src = image.toBitmap()
            val rotation = image.imageInfo.rotationDegrees

            synchronized(modelLock) {
                if (detector !== det || handModel !== hands || classifier !== cls) return   // closed meanwhile
                val pose = det.detect(src, rotation)
                val upright = upright(src, rotation)
                lastAspect = pose.imageWidth.toDouble() / pose.imageHeight
                val result = extractFrame(pose, upright, hands)
                val inferenceMs = SystemClock.elapsedRealtime() - now
                handleFrame(now, result, inferenceMs)
            }

            if (now - fpsSince >= 1000) {
                val fps = fpsCount
                _state.update { it.copy(fps = fps) }
                fpsCount = 0; fpsSince = now
            }
            fpsCount++
        } catch (t: Throwable) {
            fail("Recognition problem: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            image.close()
        }
    }

    /** One camera frame -> the 96 raw numbers of [SignFeatures] (NaN = not seen). Null when no body is in view. */
    private fun extractFrame(pose: PoseResult, upright: Bitmap, hands: HandLandmarkExtractor): FrameResult? {
        val p = pose.points
        val ls = p[Kp.L_SHOULDER]
        val rs = p[Kp.R_SHOULDER]
        if (ls.score < BODY_SCORE || rs.score < BODY_SCORE) return null
        val aspect = pose.imageWidth.toDouble() / pose.imageHeight
        val widthFrac = hypot((ls.x - rs.x) * aspect, (ls.y - rs.y).toDouble())
        if (widthFrac < SignFeatures.MIN_SHOULDER_WIDTH) return null
        val midY = (ls.y + rs.y) / 2.0

        val raw = DoubleArray(SignFeatures.RAW) { Double.NaN }
        // pose slots: left shoulder, right shoulder, left elbow, right elbow, left wrist, right wrist
        val order = intArrayOf(Kp.L_SHOULDER, Kp.R_SHOULDER, Kp.L_ELBOW, Kp.R_ELBOW, Kp.L_WRIST, Kp.R_WRIST)
        for ((slot, idx) in order.withIndex()) {
            val k = p[idx]
            if (slot < 2 || k.score >= JOINT_SCORE) {
                raw[84 + 2 * slot] = k.x.toDouble()
                raw[85 + 2 * slot] = k.y.toDouble()
            }
        }

        val bw = upright.width.toFloat()
        val bh = upright.height.toFloat()
        var overlay: SignOverlay? = null
        var overlayY = Float.MAX_VALUE
        // left hand -> columns 0..41, right hand -> columns 42..83
        val sides = arrayOf(intArrayOf(Kp.L_WRIST, Kp.L_ELBOW, 0), intArrayOf(Kp.R_WRIST, Kp.R_ELBOW, 42))
        for (s in sides) {
            val w = p[s[0]]
            val e = p[s[1]]
            if (w.score < BODY_SCORE || e.score < BODY_SCORE) continue
            if ((w.y - midY) / widthFrac > RUN_Y_MAX) continue          // hand is down: do not waste time on it

            val vx = (w.x - e.x) * bw
            val vy = (w.y - e.y) * bh
            val forearm = hypot(vx, vy)
            if (forearm < 24f) continue
            val side = (1.2f * forearm).coerceIn(64f, min(bw, bh))
            val cx = w.x * bw + 0.35f * vx
            val cy = w.y * bh + 0.35f * vy
            // turn the picture so that the forearm (elbow -> wrist) points up, as the hand model expects
            val angle = (-90.0 - Math.toDegrees(atan2(vy.toDouble(), vx.toDouble()))).toFloat()

            val hand = hands.detect(upright, cx, cy, side, angle) ?: continue
            if (hand.presence < HAND_PRESENCE) continue
            for (i in 0 until 42) raw[s[2] + i] = hand.xy[i].toDouble()

            if (w.y < overlayY) {
                overlayY = w.y
                val ux = vx / forearm
                val uy = vy / forearm
                val h = side / 2f
                val xs = FloatArray(4)
                val ys = FloatArray(4)
                var c = 0
                for (a in intArrayOf(-1, 1)) for (b in intArrayOf(-1, 1)) {
                    xs[c] = cx + a * h * ux - b * h * uy
                    ys[c] = cy + a * h * uy + b * h * ux
                    c++
                }
                overlay = SignOverlay(
                    floatArrayOf(
                        (xs.min() / bw).coerceIn(0f, 1f), (ys.min() / bh).coerceIn(0f, 1f),
                        (xs.max() / bw).coerceIn(0f, 1f), (ys.max() / bh).coerceIn(0f, 1f)
                    ),
                    floatArrayOf(e.x, e.y, w.x, w.y),
                    upright.width, upright.height
                )
            }
        }
        return FrameResult(raw, overlay)
    }

    // ---------------------------------------------------------------------------------------------
    // Frames -> signs -> words
    // ---------------------------------------------------------------------------------------------

    /** Same test as features.py uses for "a wrist is raised", but with the shoulders of this one frame. */
    private fun isActive(raw: DoubleArray, aspect: Double): Boolean {
        val cy = (raw[85] + raw[87]) / 2.0
        val width = hypot((raw[84] - raw[86]) * aspect, raw[85] - raw[87])
        if (width < SignFeatures.MIN_SHOULDER_WIDTH) return false
        val left = raw[0].isFinite() && (raw[1] - cy) / width < SignFeatures.ACTIVE_Y_MAX
        val right = raw[42].isFinite() && (raw[43] - cy) / width < SignFeatures.ACTIVE_Y_MAX
        return left || right
    }

    private fun handleFrame(now: Long, result: FrameResult?, inferenceMs: Long) {
        val raw = result?.raw
        val activeNow = raw != null && isActive(raw, lastAspect)

        if (raw != null) {
            frames.add(raw)
            if (!inSign && !activeNow) {
                // idle: only keep the last few frames, they become the margin before the next sign
                while (frames.size > SignFeatures.MARGIN) frames.removeAt(0)
            } else if (frames.size > MAX_FRAMES) {
                frames.removeAt(0)
            }
        }
        if (activeNow) {
            if (!inSign) {
                inSign = true
                signStartMs = now
                notice = ""
            }
            lastActiveMs = now
        }
        if (inSign && (now - lastActiveMs >= END_GAP_MS || now - signStartMs >= MAX_SIGN_MS)) {
            finishSign()
        }

        _state.update { s ->
            s.copy(
                status = if (inSign) SignStatus.SIGNING else SignStatus.READY,
                message = when {
                    inSign -> "Signing... lower your hands when you are done."
                    notice.isNotEmpty() -> notice
                    else -> READY_MESSAGE
                },
                confidence = lastConfidence,
                guesses = lastGuesses,
                inferenceMs = inferenceMs,
                overlay = result?.overlay,
                error = null
            )
        }
    }

    private fun finishSign() {
        val longEnough = lastActiveMs - signStartMs >= MIN_SIGN_MS
        val seq = if (longEnough) SignFeatures.buildSequence(frames, lastAspect) else null
        frames.clear()
        inSign = false

        val cls = classifier
        if (seq == null || cls == null) {
            notice = "I could not see a clear sign. Raise your hand and try again."
            return
        }
        val features = if (mirror.value) SignFeatures.mirror(seq) else seq
        val guesses = cls.classify(features)
        lastGuesses = guesses
        val best = guesses.first()
        lastConfidence = best.confidence
        if (best.confidence >= ACCEPT_THRESHOLD) {
            notice = ""
            _state.update { s ->
                s.copy(caption = best.label, history = (s.history + best.label).takeLast(HISTORY_SIZE))
            }
            if (speakEnabled.value) TemiRepository.speak(best.label)
        } else {
            notice = "Not sure (maybe \"${best.label}\"). Please sign it again."
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /** Rotates the frame upright and shrinks it. Reuses one bitmap between frames. */
    private fun upright(src: Bitmap, rotationDegrees: Int): Bitmap {
        val sideways = rotationDegrees % 180 != 0
        val uw = if (sideways) src.height else src.width
        val uh = if (sideways) src.width else src.height
        val scale = minOf(1f, MAX_SIDE_PX.toFloat() / max(uw, uh))
        val dw = max(1, (uw * scale).toInt())
        val dh = max(1, (uh * scale).toInt())

        var bmp = prep
        if (bmp == null || bmp.width != dw || bmp.height != dh) {
            bmp = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888)
            prep = bmp
            prepCanvas = Canvas(bmp)
        }
        val m = Matrix().apply {
            postTranslate(-src.width / 2f, -src.height / 2f)
            postRotate(rotationDegrees.toFloat())
            postScale(scale, scale)
            postTranslate(dw / 2f, dh / 2f)
        }
        prepCanvas!!.drawColor(android.graphics.Color.BLACK)
        prepCanvas!!.drawBitmap(src, m, prepPaint)
        return bmp
    }
}
