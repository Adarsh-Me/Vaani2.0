package com.itantra.walkie.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.itantra.walkie.AppConfig
import com.itantra.walkie.Lang
import com.itantra.walkie.Voice
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.exp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.PI
import kotlin.random.Random

/**
 * TTS: ai4bharat/IndicF5 ONNX fast preset, 24kHz zero-shot.
 * text_enc(tokens, prompt_tokens, prompt_len, speed) -> text_condition (its frame count IS
 * the utterance duration). FM euler loop on a t_shift-warped timeline over mel*FEAT_SCALE
 * space: x += fm(t,x,tc,sc,guidance)*dt. Crop prompt, divide by FEAT_SCALE, then vocos.
 */
class DhVaaniEngine(private val env: OrtEnvironment, private val res: Bundled, threads: Int = 4) : AutoCloseable {
    var lastError: String = ""
    // per-stage wall clock (ms) of the last synthesize(), for latency diagnosis
    var msEnc = 0L; var msFm = 0L; var msVoc = 0L; var msHead = 0L; var msIstft = 0L
    var lastFrames = 0; var lastCondFrames = 0
    /** Sentences the last reply was split into: every one of them carries the prompt region. */
    var lastChunks = 0
    var lastStepMs: List<Long> = emptyList()
    var lastMelStats: String = ""
    // Text-condition / velocity probe for FM-solve diagnosis. The vocoder path
    // was dark until the frontend was fixed to magnitude mel (see refMel), so
    // a dark output with a healthy-looking prompt mel points at the FM solve,
    // not the vocoder. These say whether the text encoder filled t frames.
    var tcRows = 0; var tcWanted = 0; var tcBins = 0; var tcAbs = 0.0
    var lastVelAbs = 0.0; var lastScStats = ""; var lastTcStats = ""
    /** Vocoder output layout seen on the last renderMel(), for bench diagnosis. */
    var lastVocShape = ""
    /** Prompt mel stats of the last synth (the distribution the FM should preserve). */
    var lastPromptStats = ""
    private val nThreads = threads.coerceIn(1, 8)
    private val opts = Sessions.options(threads)

    /**
     * Frames of reference clip a solve may carry. The prompt region rides through every
     * Euler step, so this number is a flat per-utterance latency cost, and [debug latbench]
     * is what decides it against voice quality. Defaults to the shipped cap.
     */
    var promptCap = AppConfig.TTS_PROMPT_MAX_FRAMES
    // Sessions come straight out of the APK: copying 812 MB of models into filesDir made
    // the install hold every weight twice.
    private fun openSess(n: String) = try {
        env.createSession(res.buffer("$TTS_DIR/$n"), opts)
    } catch (e: Exception) { lastError = "open $n: ${e.message}"; null }
    private val textEnc = openSess("text_encoder_int8.onnx")
    private val fmDec = openSess("fm_decoder_int8.onnx")
    private val voc = openSess("vocoder_backbone.onnx")

    private val tokMap: Map<String, Int> by lazy {
        try {
            res.text("$TTS_DIR/tokens.txt").lines()
                .mapNotNull { l -> val i = l.lastIndexOf('\t'); if (i < 0) null else l.substring(0, i) to (l.substring(i + 1).toIntOrNull() ?: -1) }
                .filter { it.second >= 0 }.toMap()
        } catch (_: Exception) { emptyMap() }
    }
    private val melFb: ByteArray by lazy { res.bytes("$TTS_DIR/mel_fb.npz") }
    private val vocHead: ByteArray by lazy { res.bytes("$TTS_DIR/vocos_head.npz") }
    // mel frontend @24k from mel_fb.npz
    private val fb: Array<DoubleArray> by lazy {
        val (shape, data) = Npz.floats(melFb, "fb.npy") // (513,100)
        Array(shape[0]) { b -> DoubleArray(shape[1]) { m -> data[b * shape[1] + m].toDouble() } }
    }
    private val win1024: FloatArray by lazy { Npz.floats(melFb, "window.npy").second }
    // vocos head
    private val headW: FloatArray by lazy { Npz.floats(vocHead, "linear_weight.npy").second } // (1026,512)
    private val headB: FloatArray by lazy { Npz.floats(vocHead, "linear_bias.npy").second }
    private val iwin: FloatArray by lazy { Npz.floats(vocHead, "window.npy").second }

    data class PromptPack(
        val mel: Array<DoubleArray>, val text: String, val ids: List<Int>,
        /** Internal-pause fraction of the prompt itself - the pacing a take is held to. */
        val pause: Float,
    )
    private val refCache = LinkedHashMap<String, PromptPack>()

    /**
     * The prompt region rides through every Euler step, so its frame count is a flat cost
     * added to each utterance. The mel segment and the token segment must describe the
     * SAME stretch of prompt audio: the text encoder aligns prompt_tokens against
     * prompt_features, and a region mismatch (e.g. middle mel + head tokens) poisons
     * every solve for that voice. So cropping is region-matched, and short refs are
     * kept whole (up to 320 frames) with their exact transcript.
     * Silence-trim first: bundled refs start/end with room tone, and leading silence
     * frames teach the FM solve to open every utterance with a pause/mumble.
     * NOTE: prompt PCM is deliberately NOT RMS-normalized and the transcript is used
     * verbatim: both "reference-faithful" normalizations were A/B-tested on-device
     * and made solves collapse to silence for several voices. The export's frontend
     * evidently expects raw prompt scale and exact transcripts.
     */
    private fun pack(pcm: FloatArray, text: String): PromptPack {
        var mel = trimSilentFrames(refMel(pcm))
        if (mel.isEmpty()) mel = refMel(pcm)
        val ids = textToIds(text)
        return PromptPack(mel, text, ids, pauseFraction(mel))
    }

    /**
     * The prompt window a solve actually conditions on, cut out of the packed ref on demand.
     *
     * This used to happen inside [pack], which meant the whole reference pack had to be re-melled
     * to try another length. Now the cache holds every clip whole - which is also what the load-time
     * trust gates are calibrated against - and the length is one integer the caller can change
     * between two sentences.
     */
    private fun crop(pack: PromptPack, max: Int): PromptPack {
        if (max <= 0 || pack.mel.size <= max) return pack
        val ids = pack.ids
        // Region-matched crop: the mel window [start, start+max) pairs with the proportional
        // token window, never with the head tokens. Centered, not densest: a max-energy window
        // cuts mid-phoneme at both ends and the solve inherits those garbage edges (Marathi pitch
        // jumps 33 -> 70).
        val start = (pack.mel.size - max) / 2
        val tokStart = start * ids.size / pack.mel.size
        val tokCount = maxOf(1, max * ids.size / pack.mel.size)
        val kept = ids.drop(tokStart).take(tokCount).let { if (it.isEmpty()) ids.take(tokCount) else it }
        val mel = Array(max) { pack.mel[start + it] }
        return PromptPack(mel, pack.text, kept, pauseFraction(mel))
    }

    /**
     * Fraction of mel frames that are internal pauses (below the same gate the edge
     * trim uses, so what is left here sits BETWEEN words). Zero-shot cloning copies
     * the prompt's pause habit into every utterance: a 23%-silent Kannada ref produced
     * 34%-silent Telugu output. Refs over the gate are rejected at load and the
     * language falls back to a clean donor prompt.
     */
    private fun pauseFraction(mel: Array<DoubleArray>): Float {
        if (mel.size < 8) return 0f
        val peak = mel.maxOf { it.average() }
        var n = 0
        for (f in mel) if (f.average() < peak - 6.0) n++
        return n.toFloat() / mel.size
    }

    /**
     * Median autocorrelation F0 over voiced 40 ms frames. Used once per ref at load:
     * a "male" clip tracking at 195 Hz (Telugu) or a "female" clip at 110 Hz is either
     * mislabeled or too noisy to clone, and conditioning a solve on it produces exactly
     * the wrong-pitch robotic output users hear as "the voice is broken".
     */
    private fun estimateF0(pcm: FloatArray): Float {
        val win = 960
        val f0s = ArrayList<Float>()
        var o = 0
        while (o + win * 2 <= minOf(pcm.size, 48000)) {
            var e = 0f
            for (i in o until o + win) e += pcm[i] * pcm[i]
            if (e / win > 1e-4f) {
                var bestLag = 0; var bestV = 0f
                for (lag in 24000 / 320..24000 / 70) {
                    var s = 0f
                    for (i in 0 until win - lag) s += pcm[o + i] * pcm[o + i + lag]
                    val n = s / (win - lag)
                    if (n > bestV) { bestV = n; bestLag = lag }
                }
                if (bestLag > 0 && bestV > e / win * 0.3f) f0s += 24000f / bestLag
            }
            o += win
        }
        if (f0s.isEmpty()) return 0f
        f0s.sort()
        return f0s[f0s.size / 2]
    }

    /** RMS-normalize prompt audio to the reference target_rms (0.1). */
    private fun normRms(pcm: FloatArray, target: Double = 0.1): FloatArray {
        if (pcm.isEmpty()) return pcm
        var s = 0.0
        for (v in pcm) s += v.toDouble() * v
        val rms = sqrt(s / pcm.size)
        if (rms < 1e-6 || !rms.isFinite()) return pcm
        val k = (target / rms).toFloat()
        return FloatArray(pcm.size) { pcm[it] * k }
    }

    /** Drop leading/trailing near-silent mel frames (mean log-power gate). */
    private fun trimSilentFrames(mel: Array<DoubleArray>): Array<DoubleArray> {
        if (mel.size <= 8) return mel
        val energy = DoubleArray(mel.size) { f -> mel[f].average() }
        val peak = energy.max()
        val gate = peak - 6.0 // ~6 dB-equivalent below the clip's own peak
        var s = 0; while (s < mel.size - 2 && energy[s] < gate) s++
        var e = mel.size - 1; while (e > s && energy[e] < gate) e--
        if (e - s + 1 < 8) return mel
        return mel.sliceArray(s..e)
    }

    private companion object {
        // Asset prefix: the models are streamed out of the APK, never copied to filesDir.
        const val TTS_DIR = "models/tts"
        // The FM decoder is trained on mel * FEAT_SCALE and its output has to be divided
        // back out before the vocoder.
        const val FEAT_SCALE = 0.1
        // Exact port of the reference sampler (F5-TTS/IndicF5 utils_infer + CFM.sample):
        // t = linspace(0,1) + SWAY_COEF * (cos(pi/2 * t) - 1 + t), SWAY_COEF = -1.0.
        // Sway-left packs small steps near t=0 where the text/speech alignment is
        // sketched; the later steps only embellish. A plain uniform or t-shift grid
        // under-resolves alignment and the solve lands in seed-dependent basins
        // (bright vs muffled vs shrill for identical text).
        const val SWAY_COEF = -1.0
        // Read from AppConfig so tuning doesn't need an engine edit. F5's published
        // default is 2.0; 1.0 leaves the output under-conditioned (flat/mumbly),
        // 2.5+ collapses to silence (measured in the on-device sweep).
        val GUIDANCE: Float get() = AppConfig.TTS_GUIDANCE
        val SPEED: Float get() = AppConfig.TTS_SPEED
        // Chunking: the text encoder owns duration per call, so one long call gets
        // one flat prosody contour. Short sentence-like chunks each get a natural
        // rise/fall, then crossfade together. Cap keeps every FM solve small/fast.
        const val CHUNK_MAX_CHARS = 140
        const val XFADE_SAMPLES = 3600 // 150 ms @24k, matches reference cross_fade_duration
        // Cost of one unit of dead air beyond the prompt's own, added to a take's
        // reconstruction error so a solve cannot win by going quiet.
        const val DEAD_AIR_WEIGHT = 0.35
        // F0 windows a ref may track in and still be trusted as its labeled voice.
        const val REF_F0_MALE_MAX = 165f
        const val REF_F0_FEMALE_MIN = 145f
        // Internal-pause fraction above which a ref teaches choppiness instead of voice.
        // Bundled refs split cleanly: usable ones measure 0.00-0.06, the choppy-output
        // ones 0.13-0.21, so the gate sits in that gap.
        const val REF_PAUSE_MAX = 0.10f
        // Frames of prompt mel per prompt token. Measured across all 11 languages: the
        // solve speaks at (ref ratio + 0.8) mel frames per character, so a ref that runs
        // long stretches every utterance in that language - Bengali 8.9 came back at
        // 9.8 f/c against Hindi's 7.85, i.e. 25% more stretched-out speech, which is
        // exactly what users report as "break kar kar ke". The window admits refs that
        // land output within ~12% of the rate the good languages measure at.
        const val REF_RATIO_MIN = 6.2
        const val REF_RATIO_MAX = 8.0
    }

    fun isReady() = textEnc != null && fmDec != null && voc != null

    fun preloadRef(lang: Lang, voice: Voice, wav: FloatArray, text: String) {
        refCache["${lang.name}_${voice.name}"] = pack(wav, text)
    }

    /** Load every bundled ref (assets/refs/ref_{xx}_{f,m}.txt/.wav) once. */
    fun preloadFromDir(code: (Lang) -> String) {
        try {
            val todo = ArrayList<Triple<String, Voice, String>>() // key, voice, wav path
            for (lg in Lang.values()) {
                for (v in arrayOf(Voice.F, Voice.M)) {
                    val key = "${lg.name}_${v.name}"
                    if (refCache.containsKey(key)) continue
                    val suf = if (v == Voice.F) "f" else "m"
                    val w = "refs/ref_${code(lg).lowercase()}_$suf.wav"
                    if (res.exists(w) && res.exists("refs/ref_${code(lg).lowercase()}_$suf.txt")) todo += Triple(key, v, w)
                }
            }
            // Mel front end + token map are shared; build them before fanning out.
            fb; win1024; tokMap
            // 22 clips of STFT + autocorrelation dominated startup (measured 44 s of a
            // 77 s load). Each clip is independent, so score them in parallel and insert
            // in the original order to keep refCache's preference order stable.
            val scored = arrayOfNulls<Scored>(todo.size)
            parallelRows(todo.size) { i ->
                val (key, v, w) = todo[i]
                val wav = WavIO.readBytes(res.bytes(w))
                val pcm = if (wav.sr != 24000) WavIO.resample(wav.samples, wav.sr, 24000) else wav.samples
                val packed = pack(pcm, res.text(w.removeSuffix(".wav") + ".txt").trim())
                scored[i] = Scored(key, v, packed, estimateF0(pcm))
            }
            for (r in scored) {
                if (r == null) continue
                val (trusted, line) = refVerdict(r.pack, r.f0, r.voice)
                android.util.Log.e("REFS", "${r.key} $line")
                // Absent keys fall through to a donor language in resolveRef().
                if (trusted) refCache[r.key] = r.pack
            }
        } catch (e: Exception) { lastError = "refs: ${e.message}" }
    }

    /**
     * Whether a clip may condition a solve, and the three numbers behind that call: pitch
     * against the labeled gender, internal-pause fraction, and mel frames per prompt token
     * (the solve inherits that speaking rate, which is what makes a slow ref sound choppy).
     */
    private fun refVerdict(pack: PromptPack, f0: Float, voice: Voice): Pair<Boolean, String> {
        val ratio = pack.mel.size.toDouble() / pack.ids.size.coerceAtLeast(1)
        val pitchOk = f0 == 0f || (if (voice == Voice.M) f0 <= REF_F0_MALE_MAX else f0 >= REF_F0_FEMALE_MIN)
        val trusted = pitchOk && pack.pause <= REF_PAUSE_MAX && ratio in REF_RATIO_MIN..REF_RATIO_MAX
        return Pair(trusted, "mel=${pack.mel.size} ids=${pack.ids.size} f0=${"%.0f".format(f0)} " +
            "pause=${"%.2f".format(pack.pause)} ratio=${"%.1f".format(ratio)} trusted=$trusted")
    }

    /**
     * Gate verdict for an arbitrary clip and transcript, so a candidate reference can be
     * screened before it is shipped. Same math as load time, nothing cached.
     */
    fun probeRef(pcm: FloatArray, text: String, voice: Voice): String {
        val pack = pack(pcm, text)
        return refVerdict(pack, estimateF0(pcm), voice).second
    }

    private class Scored(val key: String, val voice: Voice, val pack: PromptPack, val f0: Float)

    fun refCount() = refCache.size

    /** Prompt geometry per voice: frames and tokens must stay in the encoder's ratio. */
    fun refInfo() = refCache.entries.joinToString("; ") { "${it.key}:mel=${it.value.mel.size},ids=${it.value.ids.size}" }

    /**
     * Voice prompt lookup with a fallback chain, so every one of the 11 languages
     * speaks even when its exact ref clip is missing or was rejected at load:
     * 1. exact lang+voice, 2. donor language with the SAME voice, 3. same lang other
     * voice, 4. donor other voice, 5. Hindi (most-trained), 6. anything loaded.
     * Same-gender donor outranks same-lang other-gender because the Male/Female
     * toggle is an explicit user choice while the donor's accent is not audible as
     * a wrong voice. Without a chain, Odia fell through to whatever HashMap order
     * gave first (English) - an English male voice reading Odia text.
     */
    private fun resolveRef(lang: Lang, voice: Voice): PromptPack? {
        refCache["${lang.name}_${voice.name}"]?.let { return it }
        val other = if (voice == Voice.F) Voice.M else Voice.F
        for (donor in donorLangs(lang)) {
            refCache["${donor.name}_${voice.name}"]?.let { return it }
        }
        refCache["${lang.name}_${other.name}"]?.let { return it }
        for (donor in donorLangs(lang)) {
            refCache["${donor.name}_${other.name}"]?.let { return it }
        }
        refCache["HI_${voice.name}"]?.let { return it }
        refCache["HI_${other.name}"]?.let { return it }
        return refCache.values.firstOrNull()
    }

    private fun donorLangs(lang: Lang): List<Lang> = when (lang) {
        Lang.OR -> listOf(Lang.BN, Lang.HI)
        Lang.BN -> listOf(Lang.OR, Lang.HI)
        Lang.PA -> listOf(Lang.HI)
        Lang.GU -> listOf(Lang.HI, Lang.MR)
        Lang.KN -> listOf(Lang.TE, Lang.HI)
        Lang.TE -> listOf(Lang.KN, Lang.HI)
        Lang.TA -> listOf(Lang.ML)
        Lang.ML -> listOf(Lang.TA)
        Lang.MR -> listOf(Lang.HI)
        Lang.HI -> listOf(Lang.MR)
        Lang.EN -> listOf(Lang.HI)
    }

    /** Which ref actually served a request (for bench logs + diagnosing wrong-voice output). */
    fun refSource(lang: Lang, voice: Voice): String {
        val r = resolveRef(lang, voice) ?: return "none"
        return refCache.entries.firstOrNull { it.value === r }?.key ?: "?"
    }

    fun synthesize(
        text: String, lang: Lang, voice: Voice, nfe: Int = 8,
        guidance: Float = AppConfig.TTS_GUIDANCE, seed: Long? = null,
        promptOverride: Pair<Lang, Voice>? = null, candidates: Int = 1,
    ): FloatArray {
        val clean = text.replace("\\s+".toRegex(), " ").trim()
        if (clean.isBlank() || !isReady()) return FloatArray(0)
        // lastError is sticky by design for diagnosis; clear it per utterance so a
        // previous failure can't masquerade as the current result (in logs or UI).
        lastError = ""
        // One FM solve per sentence-like chunk: each chunk gets its own duration
        // from the text encoder (natural per-sentence prosody) instead of one flat
        // contour stretched over the whole paragraph. Renders join by crossfade so the
        // boundary is inaudible.
        val chunks = splitSentences(clean)
        lastChunks = chunks.size
        // Stage clocks are per synthChunk call; sum them so the breakdown accounts for
        // the whole utterance rather than silently describing only its last sentence.
        var tEnc = 0L; var tFm = 0L; var tVoc = 0L; var tHead = 0L; var tIstft = 0L
        var tCond = 0; var tGen = 0
        val tSteps = ArrayList<Long>()
        var acc = FloatArray(0)
        for (c in chunks) {
            val pcm = synthChunk(c, lang, voice, nfe, guidance, seed, promptOverride, candidates)
            if (pcm.isEmpty()) { lastError = "tts chunk failed: $lastError"; return FloatArray(0) }
            tEnc += msEnc; tFm += msFm; tVoc += msVoc; tHead += msHead; tIstft += msIstft
            tCond += lastCondFrames; tGen += lastFrames
            tSteps += lastStepMs
            acc = if (acc.isEmpty()) pcm else xfade(acc, pcm, XFADE_SAMPLES)
        }
        msEnc = tEnc; msFm = tFm; msVoc = tVoc; msHead = tHead; msIstft = tIstft
        lastCondFrames = tCond; lastFrames = tGen
        lastStepMs = tSteps
        return acc
    }

    /** Split on sentence terminals across all 11 scripts (plus newline). */
    private fun splitSentences(s: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        fun flush() { val t = cur.toString().trim(); if (t.isNotEmpty()) out += t; cur.clear() }
        for (ch in s) {
            cur.append(ch)
            if (ch == '।' || ch == '.' || ch == '!' || ch == '?' || ch == '۔' || ch == '\n') flush()
            else if (cur.length >= CHUNK_MAX_CHARS) {
                // Break at the last space so words are never cut mid-word.
                val idx = cur.lastIndexOf(" ")
                if (idx > CHUNK_MAX_CHARS / 2) {
                    out += cur.substring(0, idx).trim(); cur.delete(0, idx + 1)
                } else flush()
            }
        }
        flush()
        return out.filter { it.isNotBlank() }
    }

    /**
     * Raised-cosine join of two renders. Pairwise on purpose: synthesize() applies it as
     * each sentence arrives so playback can start mid-utterance. (The previous list form
     * sized the output with min(fade, len/2) but blended with min(fade, offset, len), so
     * two parts shorter than the fade wrote past the buffer.)
     */
    private fun xfade(a: FloatArray, b: FloatArray, fade: Int): FloatArray {
        val ov = minOf(fade, a.size, b.size)
        if (ov <= 0) return if (a.isEmpty()) b else FloatArray(a.size + b.size) { i -> if (i < a.size) a[i] else b[i - a.size] }
        val out = FloatArray(a.size + b.size - ov)
        a.copyInto(out, 0)
        for (k in 0 until ov) {
            val t = k.toDouble() / ov
            val g = 0.5 - 0.5 * cos(Math.PI * t) // raised-cosine crossfade hides the seam
            out[a.size - ov + k] = (out[a.size - ov + k] * (1 - g) + b[k] * g).toFloat()
        }
        b.copyInto(out, a.size, ov, b.size)
        return out
    }

    /** Per-candidate scores of the last chunk, lower = better (the minimum is picked). */
    var lastCandScores: List<Double> = emptyList()
    /** Per-candidate prompt reconstruction error of the last chunk, in scaled-mel units. */
    var lastReconErr: List<Double> = emptyList()

    private fun synthChunk(
        text: String, lang: Lang, voice: Voice, nfe: Int, guidance: Float,
        seed: Long?, promptOverride: Pair<Lang, Voice>?, candidates: Int = 1,
    ): FloatArray {
        if (text.isBlank() || !isReady()) return FloatArray(0)
        // Fewer than ~6 Euler steps cannot resolve formant movement: output goes
        // flat/monotone. The floor sits where [debug latbench] measured the collapse,
        // not above it, so the sweep can see the shape of the curve.
        val steps = nfe.coerceAtLeast(4)
        try {
            val pl = promptOverride?.first ?: lang
            val pv = promptOverride?.second ?: voice
            val rawRef = resolveRef(pl, pv)
                ?: run { lastError = "no ref"; return FloatArray(0) }
            val ref = crop(rawRef, promptCap)
            lastPromptStats = stats(ref.mel)
            val refIds = ref.ids
            val genIds = textToIds(text)
            if (genIds.isEmpty()) return FloatArray(0)
            val r = ref.mel.size
            // tokens is the TARGET text only. The encoder emits 7.5 frames per token plus
            // prompt_features_len, so feeding it the prompt tokens too makes the model
            // re-speak the reference line and inflates the sequence ~36%.
            // Chunks are already short, so a straight cap (no mid-word cut) is safe.
            val encIds = genIds.take(400)
            val cT0 = System.currentTimeMillis()
            // text encoder -- it owns the duration: the reference implementation takes
            // seqLen straight from text_condition, so we must not guess it from token counts.
            // Held flat [rows*100]: it goes straight into the FM tensors and is never
            // indexed as a matrix.
            var tc: FloatArray
            run {
                val a = OnnxTensor.createTensor(env, LongBuffer.wrap(encIds.map { it.toLong() }.toLongArray()), longArrayOf(1, encIds.size.toLong()))
                val b = OnnxTensor.createTensor(env, LongBuffer.wrap(refIds.map { it.toLong() }.toLongArray()), longArrayOf(1, refIds.size.toLong()))
                val c = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(r.toLong())), longArrayOf())
                val d = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(SPEED)), longArrayOf())
                textEnc!!.run(mapOf("tokens" to a, "prompt_tokens" to b, "prompt_features_len" to c, "speed" to d)).use { res ->
                    val ten = res.get(0) as OnnxTensor
                    val o = ten.floatBuffer // [1,rows,100] row-major
                    val rows = o.capacity() / 100
                    tcRows = rows; tcWanted = encIds.size; tcBins = if (rows == 0) 0 else 100
                    val keep = minOf(rows, r + AppConfig.TTS_MAX_FRAMES)
                    tc = FloatArray(keep * 100)
                    o.get(tc)
                    var abs = 0.0
                    for (row in 0 until keep) {
                        var m = 0f
                        val bIdx = row * 100
                        for (j in 0 until 100) { val v = kotlin.math.abs(tc[bIdx + j]); if (v > m) m = v }
                        abs += m
                    }
                    tcAbs = if (keep == 0) 0.0 else abs / keep
                }
                a.close(); b.close(); c.close(); d.close()
            }
            msEnc = System.currentTimeMillis() - cT0
            val t = tc.size / 100
            if (t <= r + 1) { lastError = "text_condition too short: $t (prompt $r)"; return FloatArray(0) }
            val g = t - r
            lastFrames = g; lastCondFrames = t
            // speech condition: prompt mel scaled by FEAT_SCALE, then zeros [t,100]
            val sc = FloatArray(t * 100)
            for (i in 0 until r) {
                val row = ref.mel[i]
                val base = i * 100
                for (m in 0 until 100) sc[base + m] = (row[m] * FEAT_SCALE).toFloat()
            }
            // euler loop over the t_shift-warped schedule
            // The FM solve is seed-sensitive: identical text can come back bright or
            // muffled depending on init noise. FULL mode solves K candidates from
            // decorrelated seeds and keeps the best-scoring audio (text encoder runs
            // once; only the FM loop + vocoder repeat, ~1.7x cost for K=2).
            val baseSeed = seed ?: (text.hashCode().toLong() * 31L + 7L)
            val k = candidates.coerceIn(1, 4)
            var best: FloatArray = FloatArray(0)
            var bestScore = Double.MAX_VALUE
            var bestIdx = -1
            val scores = ArrayList<Double>(k)
            val recons = ArrayList<Double>(k)
            val cT1 = System.currentTimeMillis()
            val stepMs = ArrayList<Long>(steps)
            for (ci in 0 until k) {
                val mel = fmSolve(tc, sc, t, steps, guidance,
                    if (k == 1) baseSeed else baseSeed xor (ci.toLong() * 0x9E3779B9L), stepMs)
                val recon = promptReconError(mel, sc, r)
                // crop prompt and undo FEAT_SCALE -- the FM works in scaled mel
                // space, the vocoder expects ordinary log-mel.
                val genMel = Array(g) { i -> DoubleArray(100) { m -> mel[(r + i) * 100 + m] / FEAT_SCALE } }
                val pcm = renderMel(genMel)
                if (pcm.isEmpty()) continue
                // A solve that drifts into long dead air still reconstructs its prompt well,
                // so hold each take to the pacing of the clip it was cloned from.
                val extraSilence = (pauseFraction(genMel) - ref.pause).coerceAtLeast(0f).toDouble()
                val s = recon + DEAD_AIR_WEIGHT * extraSilence
                scores += s; recons += recon
                if (s < bestScore) {
                    bestScore = s; best = pcm; bestIdx = ci
                    lastFrames = g; lastCondFrames = t
                    lastMelStats = stats(genMel)
                }
            }
            lastCandScores = scores
            lastReconErr = recons
            android.util.Log.e("TAKES", "k=$k picked=$bestIdx score=${f2(scores)} recon=${f2(recons)}")
            msFm = System.currentTimeMillis() - cT1
            lastStepMs = stepMs
            if (best.isEmpty()) lastError = "tts: all $k candidates empty"
            else lastError = ""
            return best
        } catch (e: Exception) {
            lastError = "tts: ${e.message}"
            return FloatArray(0)
        }
    }

    /**
     * How far one take landed from the prompt it was handed.
     *
     * The solve fills the prompt region as well as the generated frames, and what belongs in
     * that region is already known - so the error there is the model's own opinion of the take,
     * measured against ground truth rather than against a guess at what good speech looks like.
     * That distinction matters here: the previous scorer ranked by spectral centroid and
     * silence, and it rated the render the user calls their favourite voice the WORST clip in
     * the whole corpus on every prosody proxy. Mean |error| in scaled-mel units; lower is
     * better.
     */
    private fun promptReconError(x: DoubleArray, sc: FloatArray, r: Int): Double {
        if (r <= 0) return 0.0
        var e = 0.0
        for (j in 0 until r * 100) { val d = x[j] - sc[j]; e += if (d < 0) -d else d }
        return e / (r * 100.0)
    }

    private fun f2(v: List<Double>): String = v.joinToString(",") { "%.3f".format(it) }

    /** One Euler FM solve over the t_shift-warped timeline; returns flat mel-space x, [t*100]. */
    private fun fmSolve(
        tc: FloatArray, sc: FloatArray, t: Int,
        steps: Int, guidance: Float, seed: Long, stepMs: ArrayList<Long>,
    ): DoubleArray {
        // Fixed seeds repeat the exact same hiss/robotic texture every utterance.
        // Hash the text so each sentence gets fresh noise but stays reproducible.
        // An explicit seed overrides (used by the seed-sensitivity experiment).
        val rnd = java.util.Random(seed)
        val n = t * 100
        val x = DoubleArray(n) { rnd.nextGaussian() }
        // Sway-left timeline (reference CFM.sample): dense steps near t=0.
        val ts = DoubleArray(steps + 1) { i ->
            val u = i.toDouble() / steps
            u + SWAY_COEF * (cos(PI * u / 2.0) - 1.0 + u)
        }
        // ORT wraps a DIRECT buffer without copying (OrtUtil.prepareBuffer only copies heap
        // buffers) and the tensor keeps a reference to it, so x's tensor is built once and
        // refilled in place between steps. Rebuilding it per step marshalled the whole
        // [t,100] state again - 8 times per sentence, for values that were already native.
        val xBuf = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val xf = FloatArray(n)
        for (i in 0 until n) xf[i] = x[i].toFloat()
        xBuf.put(xf) // position 0 here: the tensor captures the address and remaining()
        xBuf.rewind()
        val shape = longArrayOf(1, t.toLong(), 100)
        val ca = OnnxTensor.createTensor(env, FloatBuffer.wrap(tc), shape)
        val sa = OnnxTensor.createTensor(env, FloatBuffer.wrap(sc), shape)
        val ga = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(guidance)), longArrayOf())
        val xa = OnnxTensor.createTensor(env, xBuf, shape)
        try {
            for (s in 0 until steps) {
                val s0 = System.currentTimeMillis()
                val dt = ts[s + 1] - ts[s]
                val ta = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(ts[s].toFloat())), longArrayOf())
                fmDec!!.run(mapOf("t" to ta, "x" to xa, "text_condition" to ca, "speech_condition" to sa, "guidance_scale" to ga)).use { res ->
                    // One flat bulk copy out of ORT instead of materialising nested arrays.
                    val v = (res.get(0) as OnnxTensor).floatBuffer
                    if (s == steps - 1) {
                        var sum = 0.0
                        for (row in 0 until t) {
                            var m = 0f
                            val b = row * 100
                            for (j in 0 until 100) { val a = kotlin.math.abs(v.get(b + j)); if (a > m) m = a }
                            sum += m
                        }
                        lastVelAbs = sum / t
                        lastTcStats = statsF(tc); lastScStats = statsF(sc)
                    }
                    for (i in 0 until n) x[i] += v.get(i) * dt
                }
                ta.close()
                if (s < steps - 1) {
                    for (i in 0 until n) xf[i] = x[i].toFloat()
                    xBuf.rewind(); xBuf.put(xf); xBuf.rewind()
                }
                stepMs += System.currentTimeMillis() - s0
            }
        } finally {
            ca.close(); sa.close(); ga.close(); xa.close()
        }
        return x
    }

    /**
     * FM step time against intra-op thread count and frame count, on synthetic inputs.
     * The solve is ~99% of TTS latency; measured, step cost is close to linear in
     * frames and only helps up to about half the reported cores. This is what says
     * whether the 3-4s target can be bought with scheduling or only with fewer
     * steps / fewer frames. Slow (loads the decoder once per combination) - debug only.
     */
    fun fmCostProbe(threadCounts: IntArray, frameCounts: IntArray, steps: Int = 3): String {
        val fmBytes = try { res.buffer("$TTS_DIR/fm_decoder_int8.onnx") } catch (e: Exception) { return "no fm model: ${e.message}" }
        val sb = StringBuilder()
        val rnd = java.util.Random(5)
        for (fr in frameCounts) for (tf in threadCounts) {
            val s = try {
                env.createSession(fmBytes.duplicate(), Sessions.options(tf))
            } catch (e: Exception) { sb.append("f=$fr t=$tf open-err; "); continue }
            val shape = longArrayOf(1, fr.toLong(), 100)
            try {
                val x = FloatArray(fr * 100) { rnd.nextGaussian().toFloat() }
                val tc = FloatArray(fr * 100) { rnd.nextGaussian().toFloat() }
                val sc = FloatArray(fr * 100) { rnd.nextGaussian().toFloat() }
                val ta = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(0.5f)), longArrayOf())
                val xa = OnnxTensor.createTensor(env, FloatBuffer.wrap(x), shape)
                val ca = OnnxTensor.createTensor(env, FloatBuffer.wrap(tc), shape)
                val sa = OnnxTensor.createTensor(env, FloatBuffer.wrap(sc), shape)
                val ga = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(AppConfig.TTS_GUIDANCE)), longArrayOf())
                val feed = mapOf("t" to ta, "x" to xa, "text_condition" to ca, "speech_condition" to sa, "guidance_scale" to ga)
                s.run(feed).close() // first call pays graph warm-up; never time it
                val t0 = System.currentTimeMillis()
                repeat(steps) { s.run(feed).close() }
                sb.append("f=$fr t=$tf ms/step=${(System.currentTimeMillis() - t0) / steps}; ")
                ta.close(); xa.close(); ca.close(); sa.close(); ga.close()
            } catch (e: Exception) {
                sb.append("f=$fr t=$tf err=${e.message}; ")
            } finally {
                try { s.close() } catch (_: Exception) {}
            }
        }
        return sb.toString()
    }

    /** mel [frames][100] -> 24kHz PCM. Split out so the back end can be tested in isolation. */
    fun renderMel(mel: Array<DoubleArray>): FloatArray {
        if (mel.isEmpty() || voc == null) return FloatArray(0)
        val g = mel.size
        return try {
            val cV = System.currentTimeMillis()
            val flat = FloatArray(100 * g) { i -> val m = i / g; val f = i % g; mel[f][m].toFloat() } // [1,100,G]
            val ma = OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), longArrayOf(1, 100, g.toLong()))
            val frames: Array<FloatArray> // [G][512], one 512-d vector per mel frame
            voc!!.run(mapOf("mels" to ma)).use { res ->
                val t = res.get(0) as OnnxTensor
                val shape = t.info.shape // e.g. [1,G,512] (channels-last) or [1,512,G] (channels-first)
                @Suppress("UNCHECKED_CAST")
                val batched = (t.value as Array<Array<FloatArray>>)[0]
                frames = when {
                    // [G][512]: already frame-major, use directly.
                    batched.size == g && batched[0].size == 512 -> {
                        lastVocShape = "frame-major ${shape.contentToString()}"
                        batched
                    }
                    // [512][G]: Vocos-style channels-first export. Transpose or every
                    // frame reads the wrong axis (muffled, metallic, robotic).
                    batched.size == 512 && batched[0].size == g -> {
                        lastVocShape = "channels-first ${shape.contentToString()} -> transposed"
                        Array(g) { f -> FloatArray(512) { c -> batched[c][f] } }
                    }
                    else -> {
                        lastError = "vocoder shape ${shape.contentToString()} vs frames=$g"
                        return FloatArray(0)
                    }
                }
            }
            ma.close()
            msVoc = System.currentTimeMillis() - cV
            val cH = System.currentTimeMillis()
            // head: 512 -> 1026, split mag/phase, istft
            val bins = 513
            val re = Array(g) { DoubleArray(bins) }; val im = Array(g) { DoubleArray(bins) }
            // mag=exp(raw[0:513]), ph=raw[513:1026]. Frames are independent, so fan them
            // out over the cores: this matmul is ~315M MACs and dominates non-ORT time.
            val hw = headW; val hb = headB
            parallelRows(g) { f ->
                val h = frames[f]
                val raw = FloatArray(1026)
                for (o in 0 until 1026) {
                    var s = hb[o]
                    val base = o * 512
                    for (i in 0 until 512) s += h[i] * hw[base + i]
                    raw[o] = s
                }
                val reF = re[f]; val imF = im[f]
                for (o in 0 until 513) {
                    // A few bins come back with absurd log-magnitudes that overflow exp and
                    // put Infinities through the iSTFT (audible clicks). The mel is kept in
                    // raw |X|^2 units, so magnitude scale is arbitrary anyway -- clamp it and
                    // let the output peak-normalisation set the level.
                    val m = exp(raw[o].toDouble().coerceIn(-30.0, 30.0))
                    val p = raw[513 + o].toDouble()
                    reF[o] = m * cos(p); imF[o] = m * sin(p)
                }
            }
            msHead = System.currentTimeMillis() - cH
            val cI = System.currentTimeMillis()
            val out = Fft.istft(re, im, iwin, 256, 1024)
            msIstft = System.currentTimeMillis() - cI
            finishAudio(out)
        } catch (e: Exception) { lastError = "vocode: ${e.message}"; FloatArray(0) }
    }

    /**
     * Post-condition the raw iSTFT output. Every step here targets an audible defect:
     * seam click (fade-in), DC hiss (mean removal), dead air (silence trim),
     * hard-clip distortion (0.89 headroom instead of 0.95), end-click (fade-out).
     */
    private fun finishAudio(out: FloatArray): FloatArray {
        if (out.isEmpty()) return out
        // 1. Scrub NaN/Inf first so no later stage keys off a click spike.
        for (i in out.indices) { val v = out[i]; if (v.isNaN() || v.isInfinite()) out[i] = 0f }
        // 2. DC block: the head can leave a constant offset that eats headroom and
        // makes quiet passages hiss.
        var mean = 0.0; for (v in out) mean += v
        mean /= out.size
        if (mean.isFinite() && kotlin.math.abs(mean) > 1e-6) for (i in out.indices) out[i] = (out[i] - mean).toFloat()
        // 3. Trim leading/trailing digital silence so messages start promptly and
        // don't trail room tone (~-50 dB gate, 20 ms windows).
        val gate = 0.003f
        val win = 480
        fun loud(from: Int, to: Int): Boolean {
            var s = 0.0; var n = 0
            for (i in from until to) { s += out[i] * out[i]; n++ }
            return n > 0 && kotlin.math.sqrt(s / n) > gate
        }
        var s = 0
        while (s + win < out.size && !loud(s, s + win)) s += win / 2
        var e = out.size
        while (e - win > s && !loud(e - win, e)) e -= win / 2
        var pcm = if (s > 0 || e < out.size) out.copyOfRange(s.coerceIn(0, out.size), e.coerceIn(s, out.size)) else out
        if (pcm.isEmpty()) pcm = out
        // 4. Short raised-cosine fades both ends: kills the prompt-seam impulse at
        // the head and the hard stop click at the tail.
        val fade = minOf(240, pcm.size / 4)
        for (i in 0 until fade) {
            val w = (0.5 - 0.5 * cos(kotlin.math.PI * i / fade)).toFloat()
            pcm[i] *= w
            pcm[pcm.size - 1 - i] *= w
        }
        // 5. Peak-normalise with headroom: 0.95 leaves clips on phone speakers,
        // 0.89 stays clean after the AudioTrack float->int16 quantise.
        var lo = 0f; var hi = 0f
        for (v in pcm) { if (v < lo) lo = v; if (v > hi) hi = v }
        val absMax = maxOf(kotlin.math.abs(lo), kotlin.math.abs(hi))
        if (absMax > 1e-6f) { val k = 0.89f / absMax; for (i in pcm.indices) pcm[i] *= k }
        return pcm
    }

    /** Public so the bench can vocode a known-good human mel and prove the back end works. */
    fun refMelOf(pcm: FloatArray): Array<DoubleArray> = refMel(pcm)

    /**
     * Runs the text encoder both ways (tokens = prompt+text vs text-only) and reports the
     * frame count each yields, to pin down the concatenation convention the export expects.
     */
    fun probeTextEnc(text: String, lang: Lang, voice: Voice): String {
        val ref = refCache["${lang.name}_${voice.name}"] ?: refCache.values.firstOrNull() ?: return "no ref"
        val refIds = ref.ids; val genIds = textToIds(text); val r = ref.mel.size
        fun go(ids: List<Int>): String = try {
            val a = OnnxTensor.createTensor(env, LongBuffer.wrap(ids.map { it.toLong() }.toLongArray()), longArrayOf(1, ids.size.toLong()))
            val b = OnnxTensor.createTensor(env, LongBuffer.wrap(refIds.map { it.toLong() }.toLongArray()), longArrayOf(1, refIds.size.toLong()))
            val c = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(r.toLong())), longArrayOf())
            val d = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(SPEED)), longArrayOf())
            val o = textEnc!!.run(mapOf("tokens" to a, "prompt_tokens" to b, "prompt_features_len" to c, "speed" to d)).use { res ->
                @Suppress("UNCHECKED_CAST")
                ((res.get(0) as OnnxTensor).value as Array<Array<FloatArray>>)[0]
            }
            a.close(); b.close(); c.close(); d.close()
            "n=${ids.size} -> ${o.size}f ${stats(Array(o.size) { i -> DoubleArray(o[i].size) { j -> o[i][j].toDouble() } })}"
        } catch (e: Exception) { "n=${ids.size} FAIL ${e.message}" }
        // ZipVoice-family tokenizers interleave a blank id between characters; if ours omits
        // it, the duration regulator sees half the tokens and over-predicts frames.
        val blank = tokMap["_"] ?: 0
        fun blanks(ids: List<Int>): List<Int> = buildList { ids.forEach { add(it); add(blank) } }
        return "r=$r refIds=${refIds.size} genIds=${genIds.size} | concat[${go(refIds + genIds)}]" +
            " | blanked[${go(blanks(refIds) + blanks(genIds))}]" +
            " | g2[${go(refIds + genIds.take(8))}]"
    }

    /**
     * Dump the loaded frontend constants. The npz assets verify clean on disk
     * (v1.0, C-order, stored) and Npz parses them correctly, so a mel-range
     * mismatch against an independent reimplementation means the DSP math
     * differs (e.g. power vs magnitude mel), not the zip parsing.
     */
    fun frontendDiag(pcm: FloatArray): String {
        val sb = StringBuilder()
        try {
            val w = win1024
            sb.append("win n=${w.size} min=${w.min()} max=${w.max()} sum=${w.sum()}")
        } catch (e: Exception) { sb.append("win FAIL ${e.message}") }
        try {
            val f = fb
            var mn = Double.MAX_VALUE; var mx = -Double.MAX_VALUE; var ssum = 0.0
            for (row in f) for (v in row) { if (v < mn) mn = v; if (v > mx) mx = v; ssum += v }
            val colSum = DoubleArray(f[0].size) { m -> var s = 0.0; for (row in f) s += row[m]; s }
            sb.append(" | fb ${f.size}x${f[0].size} min=$mn max=$mx sum=$ssum")
                .append(" col[min=${colSum.min()} max=${colSum.max()}]")
        } catch (e: Exception) { sb.append(" | fb FAIL ${e.message}") }
        try {
            var pmx = 0f; var rms = 0.0
            for (v in pcm) { if (kotlin.math.abs(v) > pmx) pmx = v; rms += v.toDouble() * v }
            sb.append(" | pcm n=${pcm.size} absmax=$pmx rms=${"%.4f".format(kotlin.math.sqrt(rms / pcm.size))}")
                .append(" head=${pcm.take(6).joinToString(",")}")
            val mel = refMel(pcm)
            sb.append(" | mel ${stats(mel)} frames=${mel.size}")
            val worst = mel.indices.map { i -> i to mel[i].max() }.sortedByDescending { it.second }.take(3)
            sb.append(" worstFrames=").append(worst.joinToString(",") { (fi, mv) -> "f$fi:${"%.1f".format(mv)}" })
        } catch (e: Exception) { sb.append(" | mel FAIL ${e.message}") }
        return sb.toString()
    }

    fun stats(mel: Array<DoubleArray>): String {
        var mn = Double.MAX_VALUE; var mx = -Double.MAX_VALUE; var s = 0.0; var ss = 0.0
        val n = mel.size * 100
        for (row in mel) for (v in row) { if (v < mn) mn = v; if (v > mx) mx = v; s += v; ss += v * v }
        val mean = s / n
        return "min=%.2f max=%.2f mean=%.2f sd=%.2f".format(mn, mx, mean, kotlin.math.sqrt(ss / n - mean * mean))
    }

    /** Same, for the flat [rows*100] condition tensors. */
    private fun statsF(a: FloatArray): String {
        if (a.isEmpty()) return "empty"
        var mn = Double.MAX_VALUE; var mx = -Double.MAX_VALUE; var s = 0.0; var ss = 0.0
        for (v0 in a) { val v = v0.toDouble(); if (v < mn) mn = v; if (v > mx) mx = v; s += v; ss += v * v }
        val n = a.size
        val mean = s / n
        return "min=%.2f max=%.2f mean=%.2f sd=%.2f".format(mn, mx, mean, kotlin.math.sqrt(ss / n - mean * mean))
    }

    /**
     * Run [work] for rows 0..n-1 in parallel. Deliberately NOT capped at [nThreads]: every
     * caller is pure Java between two ORT runs, so the intra-op pool is idle here and the
     * whole device is free. The 512x1026 Vocos head measured 948 ms on 2 threads for a
     * 327-frame reply - a third of a second is the difference between a 3 s and a 2 s turn.
     */
    private fun parallelRows(n: Int, work: (Int) -> Unit) {
        val nt = minOf(n, Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
        if (nt < 2) { for (f in 0 until n) work(f); return }
        val per = (n + nt - 1) / nt
        val ts = (0 until nt).map { tid ->
            Thread {
                val s = tid * per
                val e = minOf(n, s + per)
                for (f in s until e) work(f)
            }.also { it.start() }
        }
        ts.forEach { it.join() }
    }

    private fun refMel(pcm: FloatArray): Array<DoubleArray> {
        // Magnitude mel, matching the reference frontend exactly (Vocos
        // MelSpectrogramFeatures: torchaudio MelSpectrogram power=1 + safe_log,
        // i.e. ln of the magnitude mel, NOT the power mel). Feeding ln(power)
        // doubles every value in log domain and drives both the FM speech
        // conditioner and the vocoder backbone out of distribution: the
        // measured symptom was a dark/muffled spectrum (1-3kHz ~1% vs ~7% in
        // the human ref) even when vocoding a human mel directly.
        val pow = Fft.stftPower(pcm, win1024, 256, 1024) // [T][513] power
        return Array(pow.size) { f ->
            DoubleArray(100) { m ->
                var s = 0.0
                for (b in 0 until 513) s += sqrt(pow[f][b]) * fb[b][m]
                kotlin.math.ln(s.coerceAtLeast(1e-5))
            }
        }
    }

    private fun textToIds(s: String): List<Int> {
        // Normalise whitespace first: newlines/tabs collapse to a single space so the
        // duration model sees real word gaps. Dropped spaces = rushed robotic speech.
        val norm = s.replace("\\s+".toRegex(), " ").trim()
        val out = ArrayList<Int>(norm.length)
        // Iterate code points, not UTF-16 units, so astral symbols (emoji, some
        // punctuation) map as one token instead of two broken surrogates.
        var i = 0
        while (i < norm.length) {
            val cp = norm.codePointAt(i)
            i += Character.charCount(cp)
            val ch = String(Character.toChars(cp))
            // Direct hit, else the model's blank/space id keeps timing intact, else skip.
            val id = tokMap[ch] ?: tokMap["_"] ?: continue
            out += id
        }
        return out
    }

    override fun close() {
        try { textEnc?.close() } catch (_: Exception) {}
        try { fmDec?.close() } catch (_: Exception) {}
        try { voc?.close() } catch (_: Exception) {}
    }
}

object Fft2
class NpyData
