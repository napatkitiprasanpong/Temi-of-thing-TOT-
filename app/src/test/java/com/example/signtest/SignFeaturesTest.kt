package com.example.signtest

import com.example.signtest.SignFeatures
import com.example.signtest.SignFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Checks that the Kotlin feature code gives the same numbers as training/features.py.
 * The golden file is written by `python test_features.py --golden` (src/test/resources).
 */
class SignFeaturesTest {

    private fun parse(s: String): Float = if (s == "NaN") Float.NaN else s.toFloat()

    @Test
    fun matchesPythonImplementation() {
        val lines = javaClass.classLoader!!.getResourceAsStream("sign_features_golden.txt")!!
            .bufferedReader().readLines().filter { it.isNotBlank() }
        val head = lines[0].trim().split(" ")
        val nf = head[0].toInt()
        val aspect = head[1].toFloat()

        val frames = (1..nf).map { i ->
            val v = lines[i].trim().split(" ").map(::parse)
            SignFrame(
                left = v.subList(0, 42).toFloatArray(),
                right = v.subList(42, 84).toFloatArray(),
                pose = v.subList(84, 96).toFloatArray()
            )
        }
        assertEquals("EXPECT", lines[nf + 1])
        val expected = lines.drop(nf + 2).flatMap { row -> row.trim().split(" ").map(::parse) }
        assertEquals(SignFeatures.T * SignFeatures.FEATURES, expected.size)

        val actual = SignFeatures.build(frames, aspect)
        assertNotNull(actual)
        for (i in expected.indices) {
            assertEquals("value $i (step ${i / SignFeatures.FEATURES}, column ${i % SignFeatures.FEATURES})",
                expected[i], actual!![i], 1e-4f)
        }
    }
}
