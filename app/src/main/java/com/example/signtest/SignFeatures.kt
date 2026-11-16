package com.example.signtest

import kotlin.math.floor
import kotlin.math.hypot

/**
 * One analysed camera frame for the word recogniser. All coordinates are fractions (0..1) of the upright
 * camera picture; NaN (or a null hand) = not seen.
 *
 * @param left  21 left-hand landmarks as x0,y0,x1,y1,... (42 values), or null
 * @param right same for the right hand
 * @param pose  left shoulder, right shoulder, left elbow, right elbow, left wrist, right wrist as x,y (12 values)
 */
class SignFrame(val left: FloatArray?, val right: FloatArray?, val pose: FloatArray)

/**
 * Turns a clip (a list of [SignFrame]) into the fixed-size input of the word model: T steps x 98 numbers.
 * No Android classes in here on purpose, so it can be unit-tested on the JVM.
 *
 * !! Must compute exactly what training/features.py computes. SignFeaturesTest compares the two. !!
 *
 * Columns of one step: 0..41 left hand, 42..83 right hand, 84..95 pose, 96/97 left/right hand seen.
 * Coordinates are in shoulder-widths with the middle of the shoulders as origin.
 */
object SignFeatures {
    const val T = 32
    const val FEATURES = 98
    const val ACTIVE_Y_MAX = 1.6
    const val MARGIN = 2
    const val MIN_ACTIVE = 4
    private const val RAW = 96
    private const val MIN_SHOULDER_WIDTH = 0.02

    /** @return T * FEATURES numbers (row-major, step by step), or null if the clip holds no usable sign. */
    fun build(frames: List<SignFrame>, aspect: Float): FloatArray? {
        val nf = frames.size
        if (nf < MIN_ACTIVE) return null

        val raw = Array(nf) { DoubleArray(RAW) { Double.NaN } }
        for (f in 0 until nf) {
            val fr = frames[f]
            val r = raw[f]
            fr.left?.let { for (k in 0 until 42) r[k] = it[k].toDouble() }
            fr.right?.let { for (k in 0 until 42) r[42 + k] = it[k].toDouble() }
            for (k in 0 until 12) r[84 + k] = fr.pose[k].toDouble()
            var k = 0
            while (k < RAW) { r[k] *= aspect.toDouble(); k += 2 }       // x in the same unit as y
        }

        // Body frame: median shoulder positions over the whole clip.
        val lsx = median(raw, 84); val lsy = median(raw, 85)
        val rsx = median(raw, 86); val rsy = median(raw, 87)
        if (lsx.isNaN() || lsy.isNaN() || rsx.isNaN() || rsy.isNaN()) return null
        val cx = (lsx + rsx) / 2.0
        val cy = (lsy + rsy) / 2.0
        val width = hypot(lsx - rsx, lsy - rsy)
        if (width < MIN_SHOULDER_WIDTH) return null
        for (r in raw) {
            for (k in 0 until RAW) r[k] = (r[k] - (if (k % 2 == 0) cx else cy)) / width
        }

        // The sign = first to last frame with a raised hand, plus a small margin.
        var first = -1
        var last = -1
        var count = 0
        for (f in 0 until nf) {
            val r = raw[f]
            val active = (!r[0].isNaN() && r[1] < ACTIVE_Y_MAX) || (!r[42].isNaN() && r[43] < ACTIVE_Y_MAX)
            if (active) {
                if (first < 0) first = f
                last = f
                count++
            }
        }
        if (count < MIN_ACTIVE) return null
        val start = maxOf(0, first - MARGIN)
        val end = minOf(nf - 1, last + MARGIN)
        val n = end - start + 1

        // Resample to T steps; missing values are filled from the nearest frames where they were seen.
        val out = FloatArray(T * FEATURES)
        val xs = IntArray(n)
        val ys = DoubleArray(n)
        for (k in 0 until RAW) {
            var m = 0
            for (i in 0 until n) {
                val v = raw[start + i][k]
                if (!v.isNaN()) { xs[m] = i; ys[m] = v; m++ }
            }
            if (m == 0) continue
            for (t in 0 until T) {
                val pos = t * (n - 1).toDouble() / (T - 1)
                out[t * FEATURES + k] = interp(pos, xs, ys, m).toFloat()
            }
        }
        for (t in 0 until T) {
            val pos = t * (n - 1).toDouble() / (T - 1)
            val near = minOf(floor(pos + 0.5).toInt(), n - 1)
            out[t * FEATURES + 96] = if (raw[start + near][0].isNaN()) 0f else 1f
            out[t * FEATURES + 97] = if (raw[start + near][42].isNaN()) 0f else 1f
        }
        return out
    }

    /** Median of the values of one column that are not NaN (NaN when there are none). */
    private fun median(raw: Array<DoubleArray>, col: Int): Double {
        val v = ArrayList<Double>(raw.size)
        for (r in raw) if (!r[col].isNaN()) v.add(r[col])
        if (v.isEmpty()) return Double.NaN
        v.sort()
        val h = v.size / 2
        return if (v.size % 2 == 1) v[h] else (v[h - 1] + v[h]) / 2.0
    }

    /** Linear interpolation like numpy.interp: values outside the known range take the nearest known value. */
    private fun interp(pos: Double, xs: IntArray, ys: DoubleArray, m: Int): Double {
        if (pos <= xs[0]) return ys[0]
        if (pos >= xs[m - 1]) return ys[m - 1]
        var j = 0
        while (j < m - 2 && xs[j + 1] <= pos) j++
        val x0 = xs[j].toDouble()
        val x1 = xs[j + 1].toDouble()
        return ys[j] + (pos - x0) / (x1 - x0) * (ys[j + 1] - ys[j])
    }
}
