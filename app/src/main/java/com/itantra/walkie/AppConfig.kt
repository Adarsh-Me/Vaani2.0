package com.itantra.walkie

/** Single-model MT config: one stitched IndicTrans2 covers all pairs. */
object AppConfig {
    const val MT_DIR = "models/translation/indic-indic-dist-320M"
    const val MT_REPO = "ai4bharat/indictrans2-indic-indic-dist-320M"
    // ONNX INT8, greedy beam=1, cache-free decode (see TranslatorEngine) -> lowest latency
    const val MT_BEAM = 1
    const val MT_MAX_LEN = 128

    const val STT_SR = 16000

    // STT: SraVaani-1.0 (ARTPARK + IISc), FastConformer-TDT, 443.6M params over 65 Indic
    // languages. The weights are int8 behind DequantizeLinear (434.8 MB of initializers,
    // 476.5 MB file) - see scripts/fetch-sravaani.py. Absent files = silent mic; see SravaaniStt.
    const val STT_S_DIR = "models/stt-sravaani"
    const val STT_S_REPO = "ARTPARK-IISc/SraVaani-1.0"

    // Every stage here is a sequential, CPU-bound ONNX run, so intra-op threads are the
    // only parallelism available and the TTS solve is latency-critical. More is not better, and
    // the best point is a function of *physical* cores, which availableProcessors does not tell
    // us - it counts SMT siblings. Measured with `debug fmcost`:
    //   phone, 689-frame step:  3421 ms (1) · 1914 ms (3) · 2723 ms (6)
    //   4-core emulator:        2766 ms (2) · 5222 ms (4)
    //   6-vCPU emulator:        508 ms (3) · 447 ms (4) at 241 frames; 1702 ms (3) · 985 ms (4)
    //                           at 521 - so on this guest four beats three, and on a four-core
    //                           device four is the worst number on the board.
    val ORT_THREADS: Int =
        if (Runtime.getRuntime().availableProcessors() >= 6) 4 else (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(2)

    // TTS: IndicF5 ONNX fast preset
    const val TTS_DIR = "models/tts"
    const val TTS_REPO = "ai4bharat/IndicF5"
    const val TTS_SR = 24000
    // Euler steps. Flow-matching needs enough steps to resolve pitch movement:
    // 4 steps collapses intonation to a flat robotic contour. 8 is the floor
    // for intelligible prosody, 16 is smooth. Turbo stays interactive on 4 threads.
    //
    // Turbo sits on 6, measured by `debug latbench` and then `debug seedab` (three seeds,
    // same sentence, same prompt) on the demo device: 8 steps = 26.5 s, 6 = 19.2 s, 4 = 11.8 s
    // for a 3.5 s two-sentence reply. The solve cost is (prompt frames + generated frames) x
    // steps, so steps are the only dial that does not touch the conditioning. Over the three
    // seeds, 6 and 8 are the same speaker - F0 medians 145 against 146 Hz, semitone SD 6.8
    // against 6.6, and fewer abrupt (>1 semitone) frame-to-frame jumps, 100 against 120. Four
    // steps is where it breaks: 182 Hz fundamental, SD 9.2 and 79 jumps against 40. The one
    // thing 6 does change is brightness: centroid ~907 Hz against ~745, over a donor clip at
    // 672 - audible as a slightly harder voice, partly covered again by Prosody.soften.
    const val TTS_NFE_TURBO = 6 // Euler steps when Turbo is on
    const val TTS_NFE_FULL = 16 // Euler steps when Turbo is off
    // What Turbo pays for its speed: how much donor clip rides through every Euler step.
    // `debug latbench` on the 6-vCPU emulator, first sentence of a two-sentence reply:
    //   241-frame prompt, 6 steps -> 5.6 s to first audio, pitch movement 0.38 (donor 0.39)
    //   96-frame prompt,  6 steps -> 3.4 s, pitch movement 0.42, dynamics 2.7 against 1.9
    //   96-frame prompt,  4 steps -> 2.2 s, but the solve has not converged: prompt
    //                             reconstruction 0.85 against 0.70 and dynamics 3.9, which is
    //                             the flat-and-wobbly voice the ear calls robotic
    // So Turbo crops the prompt and keeps its six steps; the step count is what holds the voice
    // up and is not on the table. Full keeps the whole clip.
    const val TTS_PROMPT_MAX_FRAMES_TURBO = 96
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
    // Frames of reference clip a solve may carry (0 = uncropped). The prompt region is
    // re-processed on every Euler step, so it is a flat per-utterance latency cost.
    // 320 keeps every bundled ref whole (longest ≈ 470 frames pre-trim) so the
    // mel/token pairing stays exact; that is what Full mode speaks with.
    //
    // Cropping is the lever Turbo pulls, and it was declined once before it was taken: on
    // 2026-09-26, shown the grid, the answer was "ship 12s, keep full voice". A day later the
    // ask was first audio under 3 s, which no step count reaches, so the mode split happened
    // instead of the setting being lowered. `debug seedab` measured the cost at 6 steps over
    // three seeds: whole prompt 19.2 s, 160 frames 14.6 s, 96 frames 10.9 s, and the spectral
    // centroid of the output climbs from ~860 Hz (the donor clip itself sits at 672) to
    // ~1185 at 160 and ~1030 at 96, i.e. a brighter, harder voice. Pitch identity survives -
    // median F0 145/148/132 Hz across those three settings, and the seed-to-seed spread
    // (124-157 Hz at a fixed setting) is wider than the difference between them. So the cost
    // is timbre, not the man himself, and Prosody.soften walks part of it back.
    const val TTS_PROMPT_MAX_FRAMES = 320
}
