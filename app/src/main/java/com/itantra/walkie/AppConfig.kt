package com.itantra.walkie

/** Single-model MT config: one stitched IndicTrans2 covers all pairs. */
object AppConfig {
    const val MT_DIR = "models/translation/indic-indic-dist-320M"
    const val MT_REPO = "ai4bharat/indictrans2-indic-indic-dist-320M"
    // ONNX INT8, greedy beam=1, encoder cached, 4 threads -> lowest latency
    const val MT_BEAM = 1
    const val MT_MAX_LEN = 128

    // STT: IndicConformer Hindi CTC int8 (extensible per-lang same family)
    const val STT_DIR = "models/stt-hi"
    const val STT_REPO = "ai4bharat/indicconformer_stt_hi_hybrid_ctc_rnnt_large"
    const val STT_SR = 16000

    // Every stage here is a sequential, CPU-bound ONNX run, so intra-op threads are the
    // only parallelism available and the TTS solve is latency-critical. More is not
    // better: `debug fmcost` on the demo device measured 689-frame FM steps at
    // 3421 ms (1 thread), 1914 ms (3) and 2723 ms (6) - past the physical core count
    // the workers start fighting for the same execution units. availableProcessors
    // reports logical/SMT threads, so halve it and cap at what the curve rewarded.
    val ORT_THREADS: Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

    // TTS: IndicF5 ONNX fast preset
    const val TTS_DIR = "models/tts"
    const val TTS_REPO = "ai4bharat/IndicF5"
    const val TTS_SR = 24000
    // Euler steps. Flow-matching needs enough steps to resolve pitch movement:
    // 4 steps collapses intonation to a flat robotic contour. 8 is the floor
    // for intelligible prosody, 16 is smooth. Turbo stays interactive on 4 threads.
    const val TTS_NFE_TURBO = 8 // Euler steps when Turbo is on
    const val TTS_NFE_FULL = 16 // Euler steps when Turbo is off
    // Classifier-free guidance for the FM decoder (F5 default is 2.0). 1.0
    // under-conditions the output: muffled consonants, wandering pitch.
    // Above 2.0 the solve collapses (70% silence at 2.5 in the sweep).
    const val TTS_GUIDANCE = 2.0f
    // FM solves are seed-sensitive: the same text can come back bright or muffled
    // from init noise alone. FULL mode solves K decorrelated candidates and keeps
    // the best-scoring audio (~1.7x cost, encoder runs once). Turbo stays single.
    const val TTS_CANDIDATES_FULL = 2
    // Slightly under 1.0 opens up vowels in Indic scripts; validated at 0.95
    // (1.0 shortens durations ~18% on-device and rushes pacing).
    const val TTS_SPEED = 0.95f
    // Mel frames the flow-matching decoder may generate (24kHz, hop 256 -> ~93.75 frames/s).
    // 1500 ~= 16s of speech; the old 600 ceiling cut long sentences off mid-word.
    const val TTS_MAX_FRAMES = 1500
    // Frames of reference clip kept per prompt (0 = uncropped). The prompt region is
    // re-processed on every Euler step, so it is a flat per-utterance latency cost.
    // 320 keeps every bundled ref whole (longest ≈ 470 frames pre-trim) so the
    // mel/token pairing stays exact; cropping is a last resort, region-matched.
    const val TTS_PROMPT_MAX_FRAMES = 320
}
