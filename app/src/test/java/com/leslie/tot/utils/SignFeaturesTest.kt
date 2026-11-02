package com.leslie.tot.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Checks that SignFeatures.kt computes exactly what features.py computes. The golden file
 * (src/test/resources/features_golden.txt) was written by the Python code from the training project:
 * per case the raw frames, then the expected features, then the expected mirrored features.
 */
class SignFeaturesTest {

    private fun parse(s: String): Double = s.toDouble()   // "NaN" is understood

    @Test
    fun matchesPythonFeatures() {
        val text = javaClass.classLoader!!.getResourceAsStream("features_golden.txt")!!
            .bufferedReader().readText().lines().filter { it.isNotBlank() }
        var i = 0
        var cases = 0
        while (i < text.size) {
            val head = text[i++].split(" ")
            assertEquals("CASE", head[0])
            val n = head[1].toInt()
            val aspect = parse(head[2])
            val frames = ArrayList<DoubleArray>()
            repeat(n) { frames.add(text[i++].split(" ").map { parse(it) }.toDoubleArray()) }
            assertEquals("EXPECTED", text[i++])
            val expected = ArrayList<Float>()
            repeat(SignFeatures.T) { text[i++].split(" ").forEach { expected.add(it.toFloat()) } }
            assertEquals("MIRROR", text[i++])
            val expectedMirror = ArrayList<Float>()
            repeat(SignFeatures.T) { text[i++].split(" ").forEach { expectedMirror.add(it.toFloat()) } }
            assertEquals("END", text[i++])

            val got = SignFeatures.buildSequence(frames, aspect)
            assertNotNull(got)
            assertEquals(expected.size, got!!.size)
            for (k in expected.indices) assertEquals("feature $k", expected[k], got[k], 1e-4f)
            val gotMirror = SignFeatures.mirror(got)
            for (k in expectedMirror.indices) assertEquals("mirror $k", expectedMirror[k], gotMirror[k], 1e-4f)
            cases++
        }
        assertEquals(3, cases)
    }
}
