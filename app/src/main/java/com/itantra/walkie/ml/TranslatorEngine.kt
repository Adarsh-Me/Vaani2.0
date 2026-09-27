package com.itantra.walkie.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.itantra.walkie.Lang
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Single stitched IndicTrans2 (indic-indic-dist-320M) for ALL pairs.
 * enc(input_ids, attention_mask) -> last_hidden_state, then a greedy walk through
 * decoder_model.onnx, which is re-run over the whole partial sentence at every step.
 * enc ids: [src_lang, ...bpe..., </s>]; dec prefix: [</s>, tgt_lang].
 *
 * There used to be a second decoder export here, decoder_with_past_model.onnx, 194 MB of
 * the install. It was never run: its input list is (input_ids, encoder_attention_mask,
 * 72 past tensors) and it has no encoder_hidden_states at all, because it expects the
 * cross-attention key/values to arrive pre-computed inside those caches - which nothing
 * in this app ever produced, since only decoder_model.onnx computes them. The decode loop
 * fed encoder_hidden_states unconditionally, so the graph could not have accepted a step.
 * The export and the dead cache plumbing are gone; a real KV-cache path needs a merged
 * decoder export, not this pair.
 */

class TranslatorEngine(
    private val env: OrtEnvironment,
    res: Bundled,
    threads: Int = 4,
) : AutoCloseable {
    private val opts = Sessions.options(threads)
    private val enc = sessOrNull(env, res, "$MT_DIR/encoder_model.onnx", opts)
    private val dec = sessOrNull(env, res, "$MT_DIR/decoder_model.onnx", opts)
    private val tok = SpmTokenizer(res)

    fun isReady() = enc != null && dec != null
    var lastError: String = ""

    fun translate(text: String, src: Lang, tgt: Lang, maxLen: Int = 64): String =
        translateWith(text, src, tgt, CONV, maxLen)

    /**
     * Language-token placement variants. dict.SRC carries all 22 NLLB codes while
     * dict.TGT carries none, so the codes must enter through the encoder - but which
     * ones, and in what order, decides whether the decoder commits to the target script
     * or just paraphrases the source in its own. Feeding only the target code made every
     * pair answer in Devanagari (Marathi hid it because Marathi IS Devanagari).
     */
    fun translateWith(text: String, src: Lang, tgt: Lang, conv: Int, maxLen: Int = 64): String {
        if (src == tgt || text.isBlank() || !isReady()) return text
        try {
            // The same dictionary asymmetry that forces the output re-base below exists on the
            // way in: dict.SRC holds 75,518 Devanagari pieces against 139 Bengali and 88 Kannada,
            // so a sentence written in any other block arrives as <unk> and the model invents
            // something. Measured on device: Bengali came back as "নোভ्हेंबरমধ্যে" twenty-two
            // times over nine seconds, Kannada as a hallucinated date, Malayalam and Odia as
            // <unk> noise. Re-basing the input onto Devanagari first - the blocks are
            // akshara-aligned, so every consonant and matra keeps its sound - turns those same
            // sentences into correct Marathi, for all eight non-Devanagari languages including
            // Odia. The true source token is kept: forcing the Hindi token instead made Gujarati
            // answer "हे मला मनोरंजक वाटते" for "I am well" and left Telugu words untranslated.
            val body = tok.encode(
                if (IndicTranslit.needsRebase(src)) IndicTranslit.toDeva(text, src) else text
            ) + listOf(tok.eos)
            val s = tok.langId(src.tag); val t = tok.langId(tgt.tag)
            val srcIds = when (conv) {
                1 -> listOf(t) + body // target code only (previous behaviour)
                2 -> listOf(s, t) + body // src then tgt (IndicTrans2 pair prefix)
                3 -> listOf(t, s) + body // tgt then src
                4 -> body // no codes at all
                5 -> listOf(s) + body // src code only
                else -> listOf(s, t) + body
            }
            val prefix = if (conv == 6) listOf(tok.eos, t) else listOf(tok.eos)
            val n = srcIds.size
            val ids = OnnxTensor.createTensor(env, LongBuffer.wrap(srcIds.map { it.toLong() }.toLongArray()), longArrayOf(1, n.toLong()))
            val mask = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(n) { 1L }), longArrayOf(1, n.toLong()))
            var flatH: FloatArray? = null
            enc!!.run(mapOf("input_ids" to ids, "attention_mask" to mask)).use { r ->
                @Suppress("UNCHECKED_CAST")
                val h = ((r.get(0) as OnnxTensor).value as Array<Array<FloatArray>>)
                flatH = FloatArray(n * 512) { i -> h[0][i / 512][i % 512] }
            }
            ids.close(); mask.close()
            // The export spells every Indic target with Devanagari glyphs, so the
            // sentence has to be re-based into its own script before it is readable -
            // and before IndicF5 sees text it can pronounce as that language.
            return IndicTranslit.fromDeva(greedy(flatH ?: return text, n, prefix, maxLen), tgt)
        } catch (e: Exception) { lastError = "conv: ${e.message}"; return text }
    }

    private fun greedy(flatH: FloatArray, encLen: Int, prefix: List<Int>, maxLen: Int): String {
        val d = dec ?: return ""
        val out = ArrayList(prefix)
        val encMask = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(encLen) { 1L }), longArrayOf(1, encLen.toLong()))
        try {
            repeat(maxLen) {
                val made = ArrayList<OnnxTensor>()
                fun lb(v: List<Int>): OnnxTensor {
                    val t = OnnxTensor.createTensor(env, LongBuffer.wrap(v.map { it.toLong() }.toLongArray()), longArrayOf(1, v.size.toLong()))
                    made.add(t); return t
                }
                fun fb(a: FloatArray, sh: LongArray): OnnxTensor {
                    val t = OnnxTensor.createTensor(env, FloatBuffer.wrap(a), sh)
                    made.add(t); return t
                }
                val feed = HashMap<String, OnnxTensor>()
                feed["input_ids"] = lb(out.toList())
                feed["encoder_hidden_states"] = fb(flatH, longArrayOf(1, encLen.toLong(), 512))
                feed["encoder_attention_mask"] = encMask
                var next = tok.eos
                d.run(feed).use { r ->
                    @Suppress("UNCHECKED_CAST")
                    val logits = ((r.get(0) as OnnxTensor).value as Array<Array<FloatArray>>)
                    val row = logits[0][logits[0].size - 1]
                    var bi = 0; var bv = Float.NEGATIVE_INFINITY
                    for (i in row.indices) if (row[i] > bv) { bv = row[i]; bi = i }
                    next = bi
                }
                for (t in made) if (t !== encMask) t.close()
                if (next == tok.eos) return tok.decode(out.drop(prefix.size))
                out += next
            }
            return tok.decode(out.drop(prefix.size))
        } finally {
            try { encMask.close() } catch (_: Exception) {}
        }
    }

    override fun close() {
        try { enc?.close() } catch (_: Exception) {}
        try { dec?.close() } catch (_: Exception) {}
    }

    companion object {
        /**
         * Language codes as [src, tgt] prefixed to the encoder input. Probed on-device
         * with `debug mtconv`: target-code-only (the previous setting) answered every
         * pair in Hindi, [src,tgt] produced real Bengali/Marathi/Tamil sentences, and
         * [tgt,src] or no codes drifted into other languages entirely.
         */
        const val CONV = 2

        private const val MT_DIR = "models/translation/indic-indic-dist-320M"

        /** Zero-length key/value cache for the first decoding step. */

        fun sessOrNull(env: OrtEnvironment, res: Bundled, assetPath: String, o: OrtSession.SessionOptions): OrtSession? =
            try { env.createSession(res.buffer(assetPath), o) } catch (_: Exception) { null }
    }
}
