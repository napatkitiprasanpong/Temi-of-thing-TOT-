package com.leslie.tot.utils

import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Landmark frames -> the (32 x 98) input of sign_words.tflite.
 *
 * !!  This file is a line-by-line port of features.py from the training project. They MUST compute exactly
 *     the same thing, or the word model will answer wrongly without any error message.
 *     SignFeaturesTest.kt checks it against a golden file written by the Python code. !!
 *
 * One frame is a DoubleArray(96), NaN = not seen:
 *   0..41   left hand  (x0,y0,x1,y1,... 21 points), fractions of the upright picture (0..1)
 *   42..83  right hand
 *   84..95  pose: left shoulder, right shoulder, left elbow, right elbow, left wrist, right wrist (x,y)
 *
 * Output (row-major, 32 rows of 98): cols 0..41 left hand, 42..83 right hand, 84..95 pose, all in shoulder
 * widths with the origin in the middle of the shoulders; col 96 / 97 = left / right hand seen (1 / 0).
 */
object SignFeatures {

    const val T = 32
    const val HAND_POINTS = 21
    const val POSE_POINTS = 6
    const val RAW = 2 * HAND_POINTS * 2 + POSE_POINTS * 2     // 96
    const val FEATURES = RAW + 2                                // 98
    const val ACTIVE_Y_MAX = 1.6      // a wrist is "raised" when it is above this many shoulder-widths below the shoulders
    const val MARGIN = 2              // frames kept before the first / after the last raised-hand frame
    const val MIN_ACTIVE = 4          // fewer raised-hand frames than this: not a sign
    const val MIN_SHOULDER_WIDTH = 0.02

    /**
     * @param frames one DoubleArray(96) per camera frame, in time order (not modified)
     * @param aspect picture width / height (makes x and y the same unit)
     * @return FloatArray(t * 98), or null when there is no usable sign in the frames
     */
    fun buildSequence(frames: List<DoubleArray>, aspect: Double, t: Int = T): FloatArray? {
        val n0 = frames.size
        if (n0 < MIN_ACTIVE) return null
        val raw = Array(n0) { frames[it].copyOf() }

        // x in the same unit as y
        for (r in raw) {
            var k = 0
            while (k < RAW) { r[k] *= aspect; k += 2 }
        }

        // Per-clip body frame: median shoulder positions (robust against jitter and missing frames).
        val med = DoubleArray(4)
        for (c in 0 until 4) {
            val vals = ArrayList<Double>()
            for (r in raw) { val v = r[84 + c]; if (v.isFinite()) vals.add(v) }
            if (vals.isEmpty()) return null
            vals.sort()
            val m = vals.size
            med[c] = if (m % 2 == 1) vals[m / 2] else (vals[m / 2 - 1] + vals[m / 2]) / 2.0
        }
        val cx = (med[0] + med[2]) / 2.0
        val cy = (med[1] + med[3]) / 2.0
        val width = hypot(med[0] - med[2], med[1] - med[3])
        if (width < MIN_SHOULDER_WIDTH) return null
        for (r in raw) {
            for (k in 0 until RAW) {
                r[k] = (r[k] - (if (k % 2 == 0) cx else cy)) / width
            }
        }

        // The sign = from the first to the last frame with a raised hand, plus a small margin.
        var first = -1
        var last = -1
        var count = 0
        for (f in 0 until n0) {
            val r = raw[f]
            val active = (r[0].isFinite() && r[1] < ACTIVE_Y_MAX) || (r[42].isFinite() && r[43] < ACTIVE_Y_MAX)
            if (active) {
                if (first < 0) first = f
                last = f
                count++
            }
        }
        if (count < MIN_ACTIVE) return null
        val start = max(0, first - MARGIN)
        val end = min(n0 - 1, last + MARGIN)
        val n = end - start + 1

        // Resample to t steps. Missing values are filled from the nearest seen frames (np.interp).
        val out = FloatArray(t * FEATURES)
        val pos = DoubleArray(t) { it * (n - 1).toDouble() / (t - 1) }
        val xs = IntArray(n)
        val ys = DoubleArray(n)
        for (k in 0 until RAW) {
            var m = 0
            for (i in 0 until n) {
                val v = raw[start + i][k]
                if (v.isFinite()) { xs[m] = i; ys[m] = v; m++ }
            }
            if (m == 0) continue
            for (j in 0 until t) out[j * FEATURES + k] = interp(pos[j], xs, ys, m).toFloat()
        }
        for (j in 0 until t) {
            val near = min(floor(pos[j] + 0.5).toInt(), n - 1)
            out[j * FEATURES + 96] = if (raw[start + near][0].isFinite()) 1f else 0f
            out[j * FEATURES + 97] = if (raw[start + near][42].isFinite()) 1f else 0f
        }
        return out
    }

    /** Same as numpy.interp: linear between the known points, the first / last value outside them. */
    private fun interp(p: Double, xs: IntArray, ys: DoubleArray, m: Int): Double {
        if (p <= xs[0]) return ys[0]
        if (p >= xs[m - 1]) return ys[m - 1]
        var i = 0
        while (i < m - 2 && xs[i + 1] <= p) i++
        val x0 = xs[i].toDouble()
        val x1 = xs[i + 1].toDouble()
        return ys[i] + (ys[i + 1] - ys[i]) * (p - x0) / (x1 - x0)
    }

    /**
     * Mirror image of a (t x 98) feature array: swap the hands, swap left / right body points, negate x.
     * A left-handed signer / a mirrored camera looks exactly like this.
     */
    fun mirror(x: FloatArray, t: Int = T): FloatArray {
        val y = x.copyOf()
        val swap = intArrayOf(1, 0, 3, 2, 5, 4)
        for (j in 0 until t) {
            val o = j * FEATURES
            for (k in 0 until 42) {
                y[o + k] = x[o + 42 + k]
                y[o + 42 + k] = x[o + k]
            }
            for (s in 0 until POSE_POINTS) {
                y[o + 84 + 2 * s] = x[o + 84 + 2 * swap[s]]
                y[o + 84 + 2 * s + 1] = x[o + 84 + 2 * swap[s] + 1]
            }
            y[o + 96] = x[o + 97]
            y[o + 97] = x[o + 96]
            var k = 0
            while (k < RAW) { y[o + k] = -y[o + k]; k += 2 }
        }
        return y
    }
}
