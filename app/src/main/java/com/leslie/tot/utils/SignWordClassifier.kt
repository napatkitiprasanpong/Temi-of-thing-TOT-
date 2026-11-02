package com.leslie.tot.utils

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.exp

/**
 * Names one signed word from 32 steps of hand landmarks (assets/sign_words.tflite).
 *
 * Input float32 [1, 32, 98] built by [SignFeatures]; output [1, words] probabilities.
 * Labels: assets/sign_words_labels.txt, one per line in output order, "gloss<TAB>what temi says".
 * Not thread-safe.
 */
class SignWordClassifier(context: Context) : AutoCloseable {

    private val interpreter: Interpreter
    private val labels: List<String>
    private val input = Array(1) { Array(SignFeatures.T) { FloatArray(SignFeatures.FEATURES) } }
    private val output: Array<FloatArray>

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
        check(shape.size == 3 && shape[1] == SignFeatures.T && shape[2] == SignFeatures.FEATURES) {
            "Unexpected word model input ${shape.joinToString("x")}, expected 1x${SignFeatures.T}x${SignFeatures.FEATURES}"
        }
        val classes = interpreter.getOutputTensor(0).shape().last()
        output = Array(1) { FloatArray(classes) }

        val lines = context.assets.open(LABELS_ASSET).bufferedReader().use { r ->
            r.readLines().map { it.trim().trimStart('\uFEFF') }.filter { it.isNotEmpty() }
        }
        check(lines.size == classes) { "$LABELS_ASSET has ${lines.size} labels but the model has $classes outputs" }
        // "gloss<TAB>spoken text": show and say the spoken text
        labels = lines.map { line -> line.split('\t').let { if (it.size > 1) it[1].trim() else it[0].trim() } }
    }

    /** @param features FloatArray(32 * 98) from [SignFeatures.buildSequence] @return best guesses, best first */
    fun classify(features: FloatArray, topK: Int = 3): List<SignPrediction> {
        require(features.size == SignFeatures.T * SignFeatures.FEATURES) { "Wrong feature size ${features.size}" }
        for (t in 0 until SignFeatures.T) {
            System.arraycopy(features, t * SignFeatures.FEATURES, input[0][t], 0, SignFeatures.FEATURES)
        }
        interpreter.run(input, output)
        val probs = toProbabilities(output[0])
        return probs.indices.sortedByDescending { probs[it] }.take(topK).map { SignPrediction(labels[it], probs[it]) }
    }

    private fun toProbabilities(raw: FloatArray): FloatArray {
        var sum = 0f
        var inRange = true
        for (v in raw) {
            if (v.isNaN()) return FloatArray(raw.size)
            if (v < 0f || v > 1f) inRange = false
            sum += v
        }
        if (inRange && abs(sum - 1f) < 0.05f) return raw
        val max = raw.max()
        val e = FloatArray(raw.size) { exp((raw[it] - max).toDouble()).toFloat() }
        val total = e.sum()
        return FloatArray(raw.size) { e[it] / total }
    }

    override fun close() {
        try { interpreter.close() } catch (_: Exception) {}
    }

    companion object {
        const val MODEL_ASSET = "sign_words.tflite"
        const val LABELS_ASSET = "sign_words_labels.txt"
    }
}
