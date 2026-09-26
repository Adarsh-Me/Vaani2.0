package com.itantra.walkie.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.itantra.walkie.Lang
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * STT for the languages the specialist Hindi [ConformerStt] cannot read: Whisper-base
 * multilingual, three ONNX files from onnx-community/whisper-base (encoder_model_fp16 +
 * decoder_model_int8 + decoder_with_past_model_int8, 145 MB).
 *
 * Frontend is OpenAI's log-mel, measured rather than assumed - see [logMel].
 * 16kHz -> Hann-400 (periodic) / hop-160 / 512-point power spectrum, of which the first
 * 201 bins go through the 80 mel bands transcribed verbatim from openai/whisper's own
 * mel_filters.npz -> log10 -> clip at global max-8 -> (x+4)/4, floor-padded to 3000
 * frames. Decode is greedy over the SOT prefix
 * [startoftranscript, lang, transcribe, notimestamps].
 *
 * What it is good at, measured on the APK's own reference clips with these exact ONNX
 * files (host harness running the same three graphs, greedy, character error against the
 * transcript shipped beside each clip): English 0.029 in Latin, Tamil 0.111 in Tamil -
 * both byte-identical to what openai/whisper's own frontend produces on the same audio.
 * Everything else came back in a script the app cannot speak: Bengali, Marathi and Hindi
 * in Nastaliq, Gujarati and Punjabi romanised, Kannada and Telugu mixed, Malayalam in
 * Thai letters. That is a limit of a 74 M-parameter model, not of this frontend - the
 * exact reference frontend fails the same clips the same way - and it is why [hasVoice]
 * admits only the two languages named above. Callers see an empty transcript for the rest
 * and fall back to typed input, exactly as before this pack was bundled.
 *
 * The engine degrades gracefully: when the asset files are absent (fresh checkout
 * without scripts/fetch-whisper-base.mjs) every session is null and isReady() is false.
 */
class WhisperStt(private val env: OrtEnvironment, private val res: Bundled, threads: Int = 4) : AutoCloseable {
    var lastError: String = ""
    var lastMsEnc = 0L; var lastMsDec = 0L
    /** Why the KV cache stayed empty, if it did. */
    var lastCacheErr: String = ""
    /** Decoder steps that ran on the KV cache vs re-ran the whole prefix. full>1 means no cache. */
    var lastStepsPast = 0; var lastStepsFull = 0
    /** True when the third graph - the one that takes a cache - loaded. */
    fun hasPast() = decPast != null

    private val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads) }
    private fun sessOrNull(name: String): OrtSession? = try {
        env.createSession(res.buffer("$DIR/$name"), opts)
    } catch (e: Exception) {
        if (lastError.isBlank()) lastError = "whisper $name: ${e.message}"
        null
    }

    private val enc = sessOrNull("encoder_model_fp16.onnx")
    private val dec = sessOrNull("decoder_model_int8.onnx")
    private val decPast = sessOrNull("decoder_with_past_model_int8.onnx")

    fun isReady() = enc != null && dec != null

    /**
     * [text] is what the app may relay - empty when the engine refused the turn.
     * [raw] keeps what the model actually said, which the debug bench needs in order to
     * show a gated language as gated rather than as a blank line.
     */
    data class SttResult(val text: String, val raw: String = text)

    // ------------------------------------------------------------- tokenizer
    // Special-token ids come from the shipped tokenizer.json added_tokens
    // (<|startoftranscript|>, <|hi|>, <|transcribe|>, ...), so no id is
    // hardcoded against one export. Normal tokens decode through the GPT-2
    // byte-level BPE table in the same file (model.vocab): decode is a pure
    // concat + byte-map, no merge ranks needed.

    /** Full special content ("<|hi|>") -> id, parsed once; empty when files are absent. */
    private val special: Map<String, Int> by lazy {
        try { parseAddedTokens(res.text("$DIR/tokenizer.json")) }
        catch (_: Exception) { emptyMap() }
    }

    /** Normal token id -> GPT-2 token string (with Ġ convention), for decode only. */
    private val id2tok: Map<Int, String> by lazy {
        try { parseModelVocab(res.text("$DIR/tokenizer.json")) }
        catch (_: Exception) { emptyMap() }
    }

    /** Whisper 2-letter code per app language. */
    private fun wcode(l: Lang): String = when (l) {
        Lang.EN -> "en"; Lang.HI -> "hi"; Lang.BN -> "bn"; Lang.GU -> "gu"
        Lang.KN -> "kn"; Lang.ML -> "ml"; Lang.MR -> "mr"; Lang.PA -> "pa"
        Lang.TA -> "ta"; Lang.TE -> "te"; Lang.OR -> "or"
    }

    /**
     * The two languages the reference clips prove this model gets right, measured as
     * character error against the transcript shipped beside each clip: EN 0.029, TA 0.111.
     *
     * This is a measurement, not a preference. Bengali, Gujarati, Kannada, Malayalam,
     * Marathi, Punjabi, Telugu and Hindi all own a language token and all decoded
     * fluently - into the wrong alphabet, and the exact OpenAI frontend produced the same
     * wrong alphabet for the same clips, so this is not a wiring fault. Whisper-base's
     * weights favour Nastaliq and Latin for most Indic speech. Text in the wrong script
     * cannot be relayed: the MT model reads it as a different language and the TTS voice
     * cannot pronounce it, so a Bengali speaker would hear his own sentence read back in
     * Urdu spelling. Those languages stay typed, which is what they did before this
     * engine existed.
     */
    private val measured = setOf(Lang.EN, Lang.TA)

    /** True when this language can be recognised: models loaded, token exists, quality measured. */
    fun hasVoice(lang: Lang): Boolean {
        if (!isReady() || lang !in measured) return false
        return try { special.containsKey("<|${wcode(lang)}|>") } catch (_: Exception) { false }
    }

    /** True when the token exists, whether or not the output is good enough to use. */
    fun covers(lang: Lang): Boolean = isReady() && special.containsKey("<|${wcode(lang)}|>")

    /**
     * The Unicode block a language must come back in. Guarding on it is what keeps a
     * mispronounced [hasVoice] list from ever putting Nastaliq on the wire.
     */
    private fun blockOf(l: Lang): IntRange = when (l) {
        Lang.EN -> 0x0000..0x024F // Latin
        Lang.HI, Lang.MR -> 0x0900..0x097F
        Lang.BN -> 0x0980..0x09FF
        Lang.PA -> 0x0A00..0x0A7F
        Lang.GU -> 0x0A80..0x0AFF
        Lang.OR -> 0x0B00..0x0B7F
        Lang.TA -> 0x0B80..0x0BFF
        Lang.TE -> 0x0C00..0x0C7F
        Lang.KN -> 0x0C80..0x0CFF
        Lang.ML -> 0x0D00..0x0D7F
    }

    /** Six in ten letters must be in the right block; below that the turn is refused. */
    fun scriptOk(text: String, lang: Lang): Boolean {
        val blk = blockOf(lang)
        var letters = 0; var inBlock = 0
        for (cp in text.codePoints().toArray()) {
            if (!Character.isLetter(cp)) continue
            letters++
            if (cp in blk) inBlock++
        }
        return letters > 0 && inBlock.toDouble() / letters >= 0.6
    }

    // -------------------------------------------------------------- frontend
    private val hann: FloatArray by lazy { parseFloatArray(res.text("$DIR/hann_window_400.json")) }
    /** [257][80], same orientation as the Conformer mel table. */
    private val melMat: Array<DoubleArray> by lazy { parseFloat2d(res.text("$DIR/mel_filters_80.json")) }

    /** 80-dim Whisper log-mel, OpenAI normalisation, [T][80]. */
    private fun logMel(pcm: FloatArray): Array<DoubleArray> {
        // Three things here moved the output once it was measured against the shipped encoder:
        //  - the spectrum is a POWER one. openai/whisper writes `stft[..., :-1].abs() ** 2`; the
        //    variable is called `magnitudes` and is not one. Reading |X| instead of |X|^2 took
        //    English from 0.029 to 0.176 character error on the app's own clip.
        //  - the last frame is dropped. Centring adds one frame past the end of the signal and
        //    the reference throws it away.
        //  - the window is the periodic Hann of torch.hann_window(400), not np.hanning(400).
        // What is NOT done here, deliberately: OpenAI analyses at n_fft=400, which a radix-2
        // FFT cannot. A true 400-point DFT was written and measured, and the 512-point
        // spectrum read through the first 201 bins of the same matrix produced the identical
        // transcript in English (0.029) and identical Tamil (0.111) - while rebuilding the
        // mel triangles for 257 bins, to keep the pair "self-consistent", degraded English to
        // 0.206. The matrix the model trained on matters; the exact bin spacing does not.
        val pow = Fft.stftPower(pcm, hann, HOP, FFT) // [T][257] power
        val t = (pow.size - 1).coerceAtLeast(0)
        val bins = minOf(melMat.size, if (pow.isEmpty()) 0 else pow[0].size)
        val log = Array(t) { f ->
            DoubleArray(80) { j ->
                var s = 0.0
                for (b in 0 until bins) {
                    val w = melMat[b][j]
                    if (w > 0.0) s += pow[f][b] * w
                }
                kotlin.math.log10(s.coerceAtLeast(1e-10))
            }
        }
        var mx = Double.NEGATIVE_INFINITY
        for (row in log) for (v in row) if (v > mx) mx = v
        for (row in log) for (j in row.indices) {
            val c = row[j].coerceAtLeast(mx - 8.0)
            row[j] = (c + 4.0) / 4.0
        }
        return log
    }

    // ---------------------------------------------------------------- decode
    /**
     * Touch the decoder graphs once so the user's first transmission is not the first.
     *
     * `debug sttbench` on the emulator split the cold turn cleanly: 2.1 s of encoder against
     * 66.2 s of decode for twelve tokens - 5.5 s a token, against 0.9 s for every turn after.
     * That is the int8 decoder's weights being paged in and its kernels being built, not
     * arithmetic, so it is payable at load for about a second instead of at the microphone.
     * Two short passes of a zero hidden state is enough to walk both graphs (the full one and
     * the with-past one); what it decodes is thrown away and every clock is reset after.
     */
    fun warm() {
        val d = dec ?: return
        val sot = special["<|startoftranscript|>"] ?: return
        val en = special["<|en|>"] ?: return
        val tr = special["<|transcribe|>"] ?: return
        val nts = special["<|notimestamps|>"] ?: return
        var dim = 512
        try {
            val s = (d.inputInfo["encoder_hidden_states"]?.info as? ai.onnxruntime.TensorInfo)?.shape
            if (s != null && s.size == 3 && s[2] > 0) dim = s[2].toInt()
        } catch (_: Exception) { /* symbolic shape - the base model's 512 stands */ }
        val prefix = listOf(sot, en, tr, nts)
        val flatH = FloatArray(2 * dim)
        repeat(2) { greedy(flatH, 2, dim, prefix, special["<|endoftext|>"] ?: -1, 3) }
        lastError = ""; lastCacheErr = ""; lastStepsPast = 0; lastStepsFull = 0; lastMsDec = 0
    }

    fun transcribe(pcm16k: FloatArray, lang: Lang, maxLen: Int = MAX_LEN): SttResult {
        val e = enc ?: return SttResult("")
        val d = dec ?: return SttResult("")
        if (pcm16k.size < 1600) return SttResult("")
        val sot = special["<|startoftranscript|>"] ?: run { lastError = "whisper: no SOT token"; return SttResult("") }
        val langTok = special["<|${wcode(lang)}|>"] ?: run { lastError = "whisper: no voice token for ${lang.name}"; return SttResult("") }
        val trTok = special["<|transcribe|>"] ?: run { lastError = "whisper: no transcribe token"; return SttResult("") }
        val ntsTok = special["<|notimestamps|>"] ?: run { lastError = "whisper: no notimestamps token"; return SttResult("") }
        val eot = special["<|endoftext|>"] ?: -1
        lastStepsPast = 0; lastStepsFull = 0; lastCacheErr = ""
        // Sticky by design for diagnosis, but it must not outlive the turn that set it: the
        // bench walks every language in one pass, and a TA line reading "answered in another
        // script" - the previous language's verdict - reports a refusal that did not happen.
        lastError = ""
        return try {
            val pcm = pcm16k.copyOf(minOf(pcm16k.size, 16000 * 30)) // cap 30s
            val mel = logMel(pcm)
            // Encoder layout is [1,80,3000]: channel-major, and padded to the full 30 s window.
            // The pad is NOT zero. OpenAI normalises the whole window in one pass, so silence
            // lands on the clamp floor (max-8 -> (max-4)/4), and every real frame sits at or
            // above that floor - which is therefore exactly the value to pad with. Measured on
            // the host with these same ONNX files: zero-padded, the Hindi reference came back as
            // 16 tokens of romanisation ("Adha Gham Upaniaske Pahili Pakti Hai"); floor-padded,
            // the same clip came back as 23 tokens in an Indic spelling. The pad changes the
            // answer, and the floor is the one the trained window actually holds.
            var floor = Double.MAX_VALUE
            for (row in mel) for (v in row) if (v < floor) floor = v
            val pad = floor.toFloat()
            val arranged = FloatArray(80 * N_FRAMES) { pad }
            val n = minOf(mel.size, N_FRAMES)
            for (m in 0 until 80) for (f in 0 until n) arranged[m * N_FRAMES + f] = mel[f][m].toFloat()
            val tEnc = System.currentTimeMillis()
            var flatH: FloatArray
            var encLen = 0; var encDim = 0
            run {
                val feat = OnnxTensor.createTensor(env, FloatBuffer.wrap(arranged), longArrayOf(1, 80, N_FRAMES.toLong()))
                val feedName = e.inputNames.firstOrNull { it.contains("feature", ignoreCase = true) }
                    ?: e.inputNames.first()
                e.run(mapOf(feedName to feat)).use { r ->
                    val t = r.get(0) as OnnxTensor
                    val sh = t.info.shape // [1, T, D]
                    encLen = sh[1].toInt(); encDim = sh[sh.size - 1].toInt()
                    @Suppress("UNCHECKED_CAST")
                    val h = t.value as Array<Array<FloatArray>>
                    flatH = FloatArray(encLen * encDim) { i -> h[0][i / encDim][i % encDim] }
                }
                feat.close()
            }
            lastMsEnc = System.currentTimeMillis() - tEnc
            val tDec = System.currentTimeMillis()
            val prefix = listOf(sot, langTok, trTok, ntsTok)
            val text = greedy(flatH, encLen, encDim, prefix, eot, maxLen)
            lastMsDec = System.currentTimeMillis() - tDec
            // A transcript in the wrong alphabet is worse than no transcript: it would be
            // translated as some other language and spoken back in a script the caller never
            // used. Refuse it here, where the caller can ask for typed input instead.
            val ok = text.isEmpty() || scriptOk(text, lang)
            if (!ok) lastError = "whisper ${lang.name}: answered in another script"
            SttResult(if (ok) text else "", text)
        } catch (ex: Exception) {
            lastError = "whisper: ${ex.message}"
            SttResult("")
        }
    }

    private fun greedy(
        flatH: FloatArray, encLen: Int, encDim: Int,
        prefix: List<Int>, eot: Int, maxLen: Int,
    ): String {
        val d = dec ?: return ""
        val dp = decPast
        val out = ArrayList(prefix)
        // The KV cache is joined by name, never by position. The two decoder graphs do not
        // describe the same world: only the first pass ever sees the encoder, so decoder_model
        // also emits the cross-attention key and value - 25 outputs against the with-past
        // graph's 13 - and reading one session's results at the other's indices hands the
        // decoder a shuffled cache. Shape matters just as much: ORT hands back
        // [batch, heads, seq, head_dim], and building a three-element shape out of the first
        // three levels made ORT reject every tensor, the catch dropped the cache in silence,
        // and a two-second sentence cost 28 s because each step re-ran the whole sequence.
        // lastStepsPast/lastStepsFull say whether that is still happening: a clean run is
        // one full step and the rest on the cache.
        val cache = HashMap<String, Pair<FloatArray, LongArray>>()
        try {
            repeat(maxLen + 1) {
                val useCache = cache.isNotEmpty() && dp != null
                val sess = if (useCache) dp!! else d
                val stepIds = if (useCache) listOf(out.last()) else out.toList()
                val made = ArrayList<OnnxTensor>()
                fun lb(v: List<Int>): OnnxTensor = OnnxTensor.createTensor(
                    env, LongBuffer.wrap(v.map { x -> x.toLong() }.toLongArray()),
                    longArrayOf(1, v.size.toLong())
                ).also { made.add(it) }
                fun fb(a: FloatArray, sh: LongArray): OnnxTensor =
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(a), sh).also { made.add(it) }
                val feed = HashMap<String, OnnxTensor>()
                feed[sess.inputNames.firstOrNull { it.contains("input_id", ignoreCase = true) }
                    ?: "input_ids"] = lb(stepIds)
                sess.inputNames.firstOrNull { it.contains("encoder_hidden", ignoreCase = true) }?.let {
                    feed[it] = fb(flatH, longArrayOf(1, encLen.toLong(), encDim.toLong()))
                }
                if (useCache) {
                    // past_key_values.0.decoder.key joins to present.0.decoder.key - on the suffix
                    // after the graph's own prefix, not after the first dot, which leaves
                    // "key_values.0.decoder.key" looking up a cache keyed "0.decoder.key" and
                    // feeds the with-past graph one input out of thirteen.
                    for (nm in sess.inputNames) {
                        if (!nm.startsWith("past_key_values.")) continue
                        cache[nm.removePrefix("past_key_values.")]?.let { feed[nm] = fb(it.first, it.second) }
                    }
                    lastStepsPast++
                } else {
                    lastStepsFull++
                }
                var next = eot
                // Every output of this step is read by POSITION in this session's own output
                // list, never by name, and the cache tensors are copied straight out of the
                // tensor's float buffer. Both choices are load-bearing: the jagged
                // Object[].value cast that used to sit here threw on all 24 present outputs,
                // the exception was swallowed, and the cache stayed empty - which made the
                // decoder re-run the whole sentence once per token: 28.5 s for twelve tokens.
                val pos = sess.outputNames.toList()
                var stepErr = ""
                sess.run(feed).use { r ->
                    val logitIdx = pos.indexOfFirst { it.contains("logit", ignoreCase = true) }
                        .let { if (it >= 0) it else findLogitIdx(r) }
                    @Suppress("UNCHECKED_CAST")
                    val logits = ((r.get(logitIdx) as OnnxTensor).value as Array<Array<FloatArray>>)
                    val row = logits[0][logits[0].size - 1]
                    var bi = 0; var bv = Float.NEGATIVE_INFINITY
                    for (i in row.indices) if (row[i] > bv) { bv = row[i]; bi = i }
                    next = bi
                    for (k in pos.indices) {
                        val nm = pos[k]
                        if (!nm.startsWith("present")) continue
                        try {
                            val t = r.get(k) as OnnxTensor
                            val sh = t.info.shape
                            require(t.info.type == ai.onnxruntime.OnnxJavaType.FLOAT) { "type ${t.info.type}" }
                            val buf = t.floatBuffer
                            val f = FloatArray(buf.remaining())
                            buf.get(f)
                            cache[nm.removePrefix("present.")] = f to sh
                        } catch (e: Exception) {
                            if (stepErr.isBlank()) stepErr = "${nm}: ${e.message}"
                        }
                    }
                }
                if (cache.isEmpty() && lastCacheErr.isBlank()) lastCacheErr =
                    "after step ${out.size - prefix.size}: present=${pos.count { it.startsWith("present") }} " +
                        "pastSession=${dp != null} err=$stepErr"
                for (t in made) t.close()
                if (next == eot) return gpt2Decode(out.drop(prefix.size))
                out += next
                if (out.size > maxLen + prefix.size) return gpt2Decode(out.drop(prefix.size))
            }
            return gpt2Decode(out.drop(prefix.size))
        } catch (e: Exception) {
            lastError = "whisper greedy: ${e.message}"
            return ""
        }
    }

    /** Index of the logits output in a decoder result: the one rank-3 tensor. */
    private fun findLogitIdx(r: OrtSession.Result): Int {
        for (idx in 0 until r.size()) {
            try {
                val sh = (r.get(idx) as OnnxTensor).info.shape
                if (sh.size == 3) return idx
            } catch (_: Exception) { /* not a tensor, keep scanning */ }
        }
        return 0
    }

    // ------------------------------------------------------------ BPE decode    // GPT-2 byte-level mapping (openai/gpt-2 encode.py bytes_to_unicode).
    private val revByteMap: Map<Char, Int> by lazy {
        val bs = ArrayList<Int>()
        for (b in '!'.code..'~'.code) bs += b
        for (b in '¡'.code..'¬'.code) bs += b
        for (b in '®'.code..'ÿ'.code) bs += b
        val cs = bs.toMutableList()
        var n = 0
        for (b in 0..255) if (b !in bs) { bs += b; cs += (256 + n); n++ }
        HashMap<Char, Int>(512).also { m -> for (i in bs.indices) m[cs[i].toChar()] = bs[i] }
    }

    private fun gpt2Decode(ids: List<Int>): String {
        if (ids.isEmpty() || id2tok.isEmpty()) return ""
        val sb = StringBuilder()
        for (id in ids) {
            val t = id2tok[id] ?: continue
            if (t.startsWith("<|")) continue
            sb.append(t)
        }
        val rev = revByteMap
        val bytes = ArrayList<Byte>(sb.length)
        for (c in sb.toString()) {
            val b = rev[c] ?: continue
            bytes += b.toByte()
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
            .replace(" +".toRegex(), " ").trim()
    }

    // ---------------------------------------------------------------- parsing
    private fun parseAddedTokens(s: String): Map<String, Int> {
        val m = LinkedHashMap<String, Int>()
        // One added_tokens entry per {...} object: grab content + id inside it.
        // DOT_MATCHES_ALL is not cosmetic - the shipped tokenizer.json is pretty-printed, so
        // every entry spans eight lines and without it this matched nothing, leaving the engine
        // loaded but unable to name a single language.
        val obj = Regex("""\{[^{}]*?"content"\s*:\s*"(.*?)".*?\}""", RegexOption.DOT_MATCHES_ALL)
        val id = Regex(""""id"\s*:\s*(\d+)""")
        for (o in obj.findAll(s)) {
            val content = o.groupValues[1]
            if (!content.startsWith("<|")) continue
            val im = id.find(o.value) ?: continue
            m[content] = im.groupValues[1].toInt()
        }
        return m
    }

    /** Reads model.vocab {token: id} with escape-aware scanning; keys may hold any unicode. */
    private fun parseModelVocab(s: String): Map<Int, String> {
        val anchor = s.indexOf("\"vocab\"")
        if (anchor < 0) return emptyMap()
        val open = s.indexOf('{', anchor + 7)
        if (open < 0) return emptyMap()
        val out = HashMap<Int, String>()
        var i = open + 1
        fun skipWs() { while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t' || s[i] == ',')) i++ }
        fun readStr(): String {
            // s[i] == '"'
            val sb = StringBuilder(); i++
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (val n = s[i + 1]) {
                        '"', '\\', '/' -> { sb.append(n); i += 2 }
                        'n' -> { sb.append('\n'); i += 2 }
                        't' -> { sb.append('\t'); i += 2 }
                        'r' -> { sb.append('\r'); i += 2 }
                        'u' -> {
                            val hex = s.substring(i + 2, minOf(i + 6, s.length))
                            sb.append(hex.toIntOrNull(16)?.toChar() ?: '?'); i += 6
                        }
                        else -> { sb.append(n); i += 2 }
                    }
                    continue
                }
                if (c == '"') { i++; break }
                sb.append(c); i++
            }
            return sb.toString()
        }
        while (i < s.length) {
            skipWs()
            if (i >= s.length) break
            if (s[i] == '}') break
            if (s[i] != '"') { i++; continue }
            val key = readStr()
            while (i < s.length && (s[i] == ' ' || s[i] == ':')) i++
            var neg = false
            if (i < s.length && s[i] == '-') { neg = true; i++ }
            var v = 0
            while (i < s.length && s[i] in '0'..'9') { v = v * 10 + (s[i] - '0'); i++ }
            out[if (neg) -v else v] = key
        }
        return out
    }

    private fun parseFloatArray(s: String): FloatArray {
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

    override fun close() {
        try { enc?.close() } catch (_: Exception) {}
        try { dec?.close() } catch (_: Exception) {}
        try { decPast?.close() } catch (_: Exception) {}
    }

    private companion object {
        const val DIR = "models/stt-whisper-base"
        /** Whisper's hop, and the FFT length the radix-2 core can actually do - see [logMel]. */
        const val FFT = 512; const val HOP = 160
        /** Whisper native window: 30 s at hop-160. */
        const val N_FRAMES = 3000
        /** Short walkie-talkie turns rarely need more; caps the autoregressive loop. */
        const val MAX_LEN = 128
    }
}
