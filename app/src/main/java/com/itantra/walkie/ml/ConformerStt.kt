package com.itantra.walkie.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.ln

/**
 * STT: ai4bharat/indicconformer_stt_hi_hybrid_ctc_rnnt_large, CTC branch INT8.
 * in: audio_signal F32[1,80,T] (log-mel) + length I64[1]; out: logprobs F32[1,T,257].
 * Frontend: 16kHz, 400-tap hanning (file), hop 160, nfft 512, mel[257][80] (file),
 * ln(), per-feature mean/var norm. CTC greedy, blank=<blk>=256.
 */
class ConformerStt(private val env: OrtEnvironment, private val res: Bundled, threads: Int = 4) : AutoCloseable {
    var lastError: String = ""
    private val sess: OrtSession? =
        try {
            // Weights are embedded in this file (see scripts/merge-onnx-data.js), so the
            // graph can be handed to ORT from memory instead of a filesDir copy.
            env.createSession(res.buffer("$STT_DIR/model.int8.opt.onnx"),
                OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads) })
        } catch (e: Exception) { lastError = "sess: ${e.message}"; android.util.Log.e("Walkie", "stt sess fail", e); null }

    private val id2tok: List<String> by lazy {
        try {
            res.text("$STT_DIR/vocab.txt").lines().map { it.substringBefore(" ").trim() }
        } catch (_: Exception) { emptyList() }
    }
    private val hanning: FloatArray by lazy { parseFloatJsonArray(res.text("$STT_DIR/hanning_window.json")) }
    private val melMat: Array<DoubleArray> by lazy { parseFloat2d(res.text("$STT_DIR/mel_filters.json")) }

    private companion object {
        const val STT_DIR = "models/stt-hi"
    }

    data class SttResult(val text: String)
    fun isReady() = sess != null

    fun transcribe(pcm16k: FloatArray): SttResult {
        val s = sess ?: return SttResult("")
        if (pcm16k.size < 1600) return SttResult("")
        return try {
            val pcm = pcm16k.copyOf(minOf(pcm16k.size, 16000 * 15)) // cap 15s
            val mel = logMel(pcm) // [T][80]
            val t = mel.size
            // ONNX expects [B,80,T]: index = ((0*80 + c)*T + f)
            val arranged = FloatArray(80 * t) { i -> val c = i / t; val f = i % t; mel[f][c].toFloat() }
            val fb = FloatBuffer.wrap(arranged)
            val lb = LongBuffer.wrap(longArrayOf(t.toLong()))
            val tSig = OnnxTensor.createTensor(env, fb, longArrayOf(1, 80, t.toLong()))
            val tLen = OnnxTensor.createTensor(env, lb, longArrayOf(1))
            val ids: List<Int>
            s.run(mapOf("audio_signal" to tSig, "length" to tLen)).use { r ->
                @Suppress("UNCHECKED_CAST")
                val lp = (r.get(0) as OnnxTensor).value as Array<Array<FloatArray>>
                ids = ctcGreedy(lp[0])
            }
            tSig.close(); tLen.close()
            SttResult(decode(ids))
        } catch (e: Exception) { lastError = "stt: ${e.message}"; android.util.Log.e("Walkie", "stt run fail", e); SttResult("") }
    }

    private fun logMel(pcm: FloatArray): Array<DoubleArray> {
        val pow = Fft.stftPower(pcm, hanning, 160, 512) // [T][257]
        val t = pow.size
        val mel = Array(t) { f ->
            DoubleArray(80) { j -> var s = 0.0; for (b in 0 until 257) s += pow[f][b] * melMat[b][j]; s }
        }
        for (j in 0 until 80) {
            var m = 0.0; for (f in 0 until t) m += ln(mel[f][j] + 1e-7); m /= t
            var v = 0.0; for (f in 0 until t) { val d = ln(mel[f][j] + 1e-7) - m; v += d * d }; v = kotlin.math.sqrt(v / t + 1e-9)
            for (f in 0 until t) mel[f][j] = (ln(mel[f][j] + 1e-7) - m) / v
        }
        return mel
    }

    private fun ctcGreedy(lp: Array<FloatArray>): List<Int> {
        val out = ArrayList<Int>(); var prev = -1
        for (f in lp.indices) {
            var bi = 0; var bv = Float.NEGATIVE_INFINITY
            for (c in lp[f].indices) if (lp[f][c] > bv) { bv = lp[f][c]; bi = c }
            if (bi != 256 && bi != prev) out += bi
            prev = bi
        }
        return out
    }

    private fun decode(ids: List<Int>): String {
        val sb = StringBuilder()
        for (id in ids) sb.append(id2tok.getOrElse(id) { "" })
        return sb.toString().replace("▁", " ").trim().replace(Regex(" +"), " ")
    }

    private fun parseFloatJsonArray(s: String): FloatArray {
        val nums = Regex("""-?\d+(\.\d+)?([eE][+-]?\d+)?""").findAll(s).map { it.value.toFloat() }.toList()
        return nums.toFloatArray()
    }

    private fun parseFloat2d(s: String): Array<DoubleArray> {
        val rows = ArrayList<DoubleArray>()
        var depth = 0; var cur: MutableList<Double>? = null
        val num = StringBuilder()
        fun flush() { val cc = cur; if (num.isNotEmpty() && cc != null) { cc.add(num.toString().toDouble()); num.clear() } }
        for (c in s) {
            when {
                c == '[' -> { depth++; if (depth == 2) cur = ArrayList() }
                c == ']' -> { flush(); if (depth == 2 && cur != null) rows.add(cur.toDoubleArray()); depth-- }
                (c in '0'..'9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E' -> num.append(c)
                else -> flush()
            }
        }
        return rows.toTypedArray()
    }

    override fun close() { try { sess?.close() } catch (_: Exception) {} }
}
