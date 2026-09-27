package com.itantra.walkie.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.itantra.walkie.Lang
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The microphone for every language the app claims: SraVaani-1.0 (ARTPARK + IISc, MIT), a
 * FastConformer-TDT with 443.6M parameters trained on 65 Indic languages. It replaces
 * Whisper-base, which owned a token for ten of the app's languages and returned the right
 * alphabet for two of them.
 *
 * Two properties make it the right size of risk. It is Indic-first, so a Bengali sentence
 * comes back in Bengali rather than in whichever script a 74M multilingual model prefers; and
 * it has no language token at all - one graph transcribes all 65 languages, so adding a
 * language to the app cannot silently disable the microphone.
 *
 * The pack is 520 MB: an encoder whose fp32 Conv weights have been rewritten to per-channel
 * int8 behind DequantizeLinear (638 -> 477 MB, measured quality-neutral, see
 * scripts/fetch-sravaani.py), the 43 MB joint decoder, and the frontend constants dumped out
 * of NeMo's preproc.pt rather than re-derived.
 *
 * Frontend is NeMo's fbank, not Whisper's log-mel: pre-emphasis 0.97, Hann-400 centred in a
 * 512-point frame, hop 160, CONSTANT edge padding (Whisper reflects - the two models disagree
 * on the boundary), power spectrum, 128 mel bands, log(x + 5.96e-8), then per-feature
 * mean/variance normalisation over the valid frames with the n-1 denominator, and the trailing
 * partial frame filled with the pad value.
 *
 * Decoding is the reference greedy TDT loop: one joint-decoder call per emitted symbol AND per
 * skipped frame, each carrying the LSTM state forward, with the predicted duration deciding how
 * many encoder frames to consume and a run of ten symbols in one frame forcing a step so a
 * silent stretch cannot stall the loop.
 */
class SravaaniStt(private val env: OrtEnvironment, private val res: Bundled, threads: Int = 4) : AutoCloseable {
    var lastError: String = ""
    var lastMsEnc = 0L; var lastMsDec = 0L
    /** Encoder frames the last utterance produced - the loop's work is proportional to this. */
    var lastFrames = 0

    private val opts = Sessions.options(threads)

    /**
     * Mappings handed to a session, held for that session's lifetime. `FileChannel.map` documents
     * that a region may be unmapped once the buffer representing it becomes unreachable, and
     * ONNX Runtime reads weights out of these pages while it runs - a collected buffer would mean
     * a SIGSEGV in the middle of a transcription, so the reference is part of the session's state.
     */
    private val pinned = ArrayList<ByteBuffer>()

    /**
     * The encoder is mapped and the decoder is read from the APK: 477 MB against 43 MB, and only
     * the first is bigger than the process can hold in native memory. See [Bundled.mapped], and
     * the log line naming which of the two sources the mapping came from - that is the proof the
     * 477 MB copy in filesDir is gone rather than a comment claiming it is.
     */
    private fun sessOrNull(name: String, large: Boolean): OrtSession? = try {
        val path = "$DIR/$name"
        when (val src = if (large) res.mapped(path) else null) {
            is Bundled.Large.Buffer -> {
                pinned += src.buf
                android.util.Log.e("STT", "$name mapped out of the APK, no extract")
                env.createSession(src.buf, opts)
            }
            is Bundled.Large.File -> {
                android.util.Log.e("STT", "$name extracted to ${src.file.name} (asset is compressed)")
                env.createSession(src.file.path, opts)
            }
            null -> env.createSession(res.buffer(path), opts)
        }
    } catch (e: Exception) {
        if (lastError.isBlank()) lastError = "sravaani $name: ${e.message}"
        null
    }

    private val enc = sessOrNull("encoder.qdq.onnx", large = true)
    private val dj = sessOrNull("decoder_joint.onnx", large = false)

    fun isReady() = enc != null && dj != null

    data class SttResult(val text: String, val raw: String = text)

    // ------------------------------------------------------------------ config
    /**
     * The frontend numbers come from the pack, not from this file, but what the pack says is
     * checked rather than assumed: a future export that changes the normalisation or the edge
     * policy would otherwise produce features that are quietly wrong, and the only symptom
     * would be a worse transcript in one language.
     */
    private val fp: Map<String, String> by lazy { JsonAssets.flat(res.text("$DIR/fbank_params.json")) }
    private val nFft by lazy { fp.num("n_fft").toInt() }
    private val hop by lazy { fp.num("hop_length").toInt() }
    private val preemph by lazy { fp.num("preemph") }
    private val guard by lazy { fp.num("log_zero_guard_value") }
    private val padValue by lazy { fp.num("pad_value") }

    private val window: FloatArray by lazy { JsonAssets.floats(res.text("$DIR/fbank_window.json")) }
    /** [257][128], indexed melMat[bin][mel] like every other mel table in this app. */
    private val melMat: Array<DoubleArray> by lazy { JsonAssets.matrix(res.text("$DIR/fbank_matrix.json")) }
    private val vocab: List<String> by lazy { JsonAssets.stringList(res.text("$DIR/vocab.json")) }

    // ---------------------------------------------------------------- frontend
    /** Fbank [1,FEAT,T] channel-major plus the number of frames the model should believe. */
    private fun fbank(pcm: FloatArray): Pair<FloatArray, Int> {
        require(fp["normalize"] == "\"per_feature\"" || fp["normalize"] == "per_feature") {
            "sravaani: unexpected normalize=${fp["normalize"]}"
        }
        require(fp.num("mag_power") == 2.0) { "sravaani: mag_power ${fp["mag_power"]} needs a different spectrum" }
        val y = FloatArray(pcm.size)
        if (y.isNotEmpty()) {
            y[0] = pcm[0]
            for (i in 1 until y.size) y[i] = pcm[i] - (preemph * pcm[i - 1]).toFloat()
        }
        val pow = Fft.stftPower(y, window, hop, nFft, reflect = false) // [T][257] power
        val t = pow.size
        val seqLen = y.size / hop
        if (seqLen < 2) return FloatArray(0) to 0
        val bins = minOf(melMat.size, pow[0].size)
        val x = Array(t) { f ->
            DoubleArray(FEAT) { m ->
                var s = 0.0
                for (b in 0 until bins) {
                    val w = melMat[b][m]
                    if (w > 0.0) s += pow[f][b] * w
                }
                ln(s + guard)
            }
        }
        for (m in 0 until FEAT) {
            var mean = 0.0
            for (f in 0 until seqLen) mean += x[f][m]
            mean /= seqLen
            var v = 0.0
            for (f in 0 until seqLen) { val d = x[f][m] - mean; v += d * d }
            var std = sqrt(v / (seqLen - 1))
            if (std.isNaN()) std = 0.0
            std += fp.num("CONSTANT")
            for (f in 0 until t) x[f][m] = if (f < seqLen) (x[f][m] - mean) / std else padValue
        }
        val out = FloatArray(FEAT * t) { i -> x[i % t][i / t].toFloat() }
        return out to seqLen
    }

    // ------------------------------------------------------------------- decode
    /**
     * One turn of audio. [lang] selects nothing in the model - it has no language token - it
     * only says which alphabet the answer has to arrive in, which is the whole reason this
     * engine exists and the reason the re-basing below is not cosmetic.
     */
    fun transcribe(pcm16k: FloatArray, lang: Lang): SttResult {
        val e = enc ?: return SttResult("")
        val d = dj ?: return SttResult("")
        if (pcm16k.size < 1600) return SttResult("")
        return try {
            val pcm = pcm16k.copyOf(minOf(pcm16k.size, 16000 * MAX_SECONDS))
            val (fe, seqLen) = fbank(pcm)
            if (fe.isEmpty()) return SttResult("")
            val t = fe.size / FEAT
            val tEnc = System.currentTimeMillis()
            var encOut = FloatArray(0); var tp = 0
            run {
                val sig = OnnxTensor.createTensor(env, FloatBuffer.wrap(fe), longArrayOf(1, FEAT.toLong(), t.toLong()))
                // The frame count, not the count of frames inside the audio. NeMo's own export
                // graph is fed this way by the reference script that produced every number in
                // this file's measurements, and one frame of trailing padding is what the
                // subsampling convolutions were pointed at; "correcting" it to seqLen shifts
                // every output length by one and the transcript goes with it.
                val len = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(t.toLong())), longArrayOf(1))
                e.run(mapOf(e.inputNames.first() to sig, e.inputNames.last() to len)).use { r ->
                    val o = r.get(0) as OnnxTensor
                    val sh = o.info.shape // [1, 1024, T']
                    tp = sh[sh.size - 1].toInt()
                    val b = o.floatBuffer
                    encOut = FloatArray(b.remaining()); b.get(encOut)
                }
                sig.close(); len.close()
            }
            lastMsEnc = System.currentTimeMillis() - tEnc
            lastFrames = tp
            val tDec = System.currentTimeMillis()
            val toks = tdt(encOut, tp, d)
            lastMsDec = System.currentTimeMillis() - tDec
            val raw = pieces(toks)
            val text = reBase(raw, lang)
            SttResult(text, raw)
        } catch (ex: Exception) {
            lastError = "sravaani: ${ex.message}"
            SttResult("")
        }
    }

    /**
     * The greedy time-unconditional transducer (TDT) walk, ported from the model's own
     * `_greedy_one`. Each call consumes one encoder frame or emits one symbol: the decoder
     * returns 5001 class scores plus five duration scores, and the winning duration says how
     * far the frame pointer advances. A duration of zero means "another symbol may follow at
     * this same frame", which is why the inner loop is bounded - without that bound a frame the
     * model reads as ten symbols would run on, and without advancing on the tenth the loop
     * would never leave it.
     */
    private fun tdt(encOut: FloatArray, tp: Int, d: OrtSession): List<Int> {
        val names = d.inputNames.toList()
        val fName = names.firstOrNull { it == "f" } ?: names[0]
        val tgtName = names.firstOrNull { it == "tgt" } ?: names[1]
        val tlenName = names.firstOrNull { it == "tlen" } ?: names[2]
        val hName = names.firstOrNull { it == "h_in" } ?: names[3]
        val cName = names.firstOrNull { it == "c_in" } ?: names[4]
        var h = FloatArray(PRED_HIDDEN)
        var c = FloatArray(PRED_HIDDEN)
        var last = BLANK
        val toks = ArrayList<Int>()
        val f = FloatArray(D_MODEL)
        val tlenBuf = IntBuffer.wrap(intArrayOf(1))
        var t = 0
        while (t < tp) {
            var added = 0; var need = true
            while (need && added < MAX_SYMBOLS) {
                for (m in 0 until D_MODEL) f[m] = encOut[m * tp + t]
                val made = ArrayList<OnnxTensor>(5)
                try {
                    val tf = OnnxTensor.createTensor(env, FloatBuffer.wrap(f), longArrayOf(1, D_MODEL.toLong(), 1)).also { made += it }
                    val tt = OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(last)), longArrayOf(1, 1)).also { made += it }
                    val tl = OnnxTensor.createTensor(env, tlenBuf.duplicate(), longArrayOf(1)).also { made += it }
                    val th = OnnxTensor.createTensor(env, FloatBuffer.wrap(h), longArrayOf(1, 1, PRED_HIDDEN.toLong())).also { made += it }
                    val tc = OnnxTensor.createTensor(env, FloatBuffer.wrap(c), longArrayOf(1, 1, PRED_HIDDEN.toLong())).also { made += it }
                    d.run(mapOf(fName to tf, tgtName to tt, tlenName to tl, hName to th, cName to tc)).use { r ->
                        val lb = (r.get(0) as OnnxTensor).floatBuffer
                        var k = 0; var bv = Float.NEGATIVE_INFINITY
                        for (i in 0..BLANK) { val v = lb.get(i); if (v > bv) { bv = v; k = i } }
                        var di = 0; var dv = Float.NEGATIVE_INFINITY
                        for (i in 0 until NUM_DUR) {
                            val v = lb.get(VOCAB + 1 + i)
                            if (v > dv) { dv = v; di = i }
                        }
                        val skip = DURATIONS[di]
                        if (k != BLANK) {
                            toks += k
                            val hb = (r.get(2) as OnnxTensor).floatBuffer; hb.get(h)
                            val cb = (r.get(3) as OnnxTensor).floatBuffer; cb.get(c)
                            last = k
                        }
                        added++
                        t += skip
                        need = skip == 0
                    }
                } finally {
                    for (x in made) x.close()
                }
            }
            if (added == MAX_SYMBOLS) t++
        }
        return toks
    }

    /**
     * Pieces back into text. `<unk>` is dropped rather than kept as a placeholder: it lands
     * where a danda or a comma was, and the surrounding pieces already carry their own
     * word-start marker, so nothing runs together.
     */
    private fun pieces(ids: List<Int>): String {
        val sb = StringBuilder()
        for (id in ids) {
            val p = vocab.getOrElse(id) { "" }
            when {
                p.isEmpty() || p == UNK -> {}
                p.startsWith(WORD_SEP) -> { if (sb.isNotEmpty()) sb.append(' '); sb.append(p.substring(1)) }
                else -> sb.append(p)
            }
        }
        return sb.toString().replace("  ".toRegex(), " ").trim()
    }

    /**
     * The model answers in one of a handful of scripts - mostly the language's own, sometimes
     * Devanagari for a sister language that shares the inventory - and the app can only speak
     * and translate what arrives in the alphabet it was told about. The Brahmic blocks are
     * akshara-aligned, so a Devanagari answer for a Gujarati or Punjabi turn is a correct
     * sentence with the wrong code points, and re-basing the block fixes it exactly.
     */
    private fun reBase(text: String, lang: Lang): String = IndicTranslit.rebaseScript(text, lang)

    /**
     * The microphone is open in ten of the app's eleven languages, measured on the bundled
     * reference clips through this pack with the re-basing above applied - character error
     * against the transcript that ships beside each clip:
     *
     *   EN 0.000-0.040   HI 0.000 (male + female)   MR 0.000   BN 0.034   ML 0.000 (x3)
     *   TA 0.000-0.056   TE 0.000   KN 0.000 (both, one only after a Telugu->Kannada shift)
     *   PA 0.133-0.182 (only after a Devanagari->Gurmukhi shift)
     *   GU 0.000 on the female clip, 0.941 on the male one - the one language here where the
     *      evidence is half in favour.
     *
     * Twenty-one of the twenty-two clips pass a 0.35 bar. Odia is the exception that stays
     * typed: the model claims it, but no Odia clip ships with the app, so there is nothing to
     * have measured and this file does not open a door it has not walked through.
     */
    private val measured = setOf(Lang.EN, Lang.HI, Lang.MR, Lang.BN, Lang.GU, Lang.KN,
                                 Lang.ML, Lang.PA, Lang.TA, Lang.TE)

    fun hasVoice(lang: Lang): Boolean = isReady() && lang in measured

    fun covers(lang: Lang): Boolean = isReady()

    private fun Map<String, String>.num(k: String): Double =
        (this[k] ?: throw IllegalStateException("sravaani: no $k in fbank_params.json")).toDouble()

    override fun close() {
        try { enc?.close() } catch (_: Exception) {}
        try { dj?.close() } catch (_: Exception) {}
        try { opts.close() } catch (_: Exception) {}
        pinned.clear() // only safe after the sessions that read from them are gone
    }

    private companion object {
        const val DIR = "models/stt-sravaani"
        /** Mel bands the encoder is conditioned on. */
        const val FEAT = 128
        const val VOCAB = 5000
        const val BLANK = 5000
        const val NUM_DUR = 5
        const val MAX_SYMBOLS = 10
        const val D_MODEL = 1024
        const val PRED_HIDDEN = 640
        const val UNK = "<unk>"
        const val WORD_SEP = "▁"
        /** Walkie turns are short; the encoder cost is linear in frames so a cap bounds latency. */
        const val MAX_SECONDS = 20
        /** NeMo's duration classes, in the order the decoder's last five scores arrive in. */
        val DURATIONS = intArrayOf(0, 1, 2, 3, 4)
    }
}
