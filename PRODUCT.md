# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

Primary: **field teams working with no network** — disaster-response crews, community health
workers, border and rural field staff. They carry ordinary Android phones, often have no SIM
data or cellular coverage, and must exchange information with people who do not speak their
language. The job is: say or type something now, and have the other person understand it out
loud in their own language, with no infrastructure nearby.

Secondary: iTantra evaluating and demonstrating its own on-device speech stack.

## Product Purpose

VANI is a walkie-talkie that translates. Speech in one Indian language becomes spoken speech in
another, entirely on the phones, over Bluetooth — no server, no internet, no account. Success
means a two-person conversation across a language barrier with nothing but the devices present.

## Positioning

Every stage runs on the handset: on-device speech recognition, an on-device translation model,
and on-device speech synthesis, with peers discovered and reached over a Bluetooth mesh. A
competitor that calls a cloud speech/translation API cannot copy the operating condition —
working in a flood, a border post, or a village with no network at all. The offline-ness is the
product, not a feature.

## Operating Context

- Open the app, choose which languages you speak once at setup.
- Bluetooth comes on and the phone joins a mesh with nearby VANI phones; the people in range are
  listed, each showing the display name that phone set.
- Set your own display name so others recognise you in their scan.
- Talk to one specific device, or broadcast to everyone in the mesh at once.
- Press and hold the mic, speak, release: your words are transcribed, translated, and spoken in
  the listener's language. Text can be typed instead.
- The reply is spoken back in the tone the caller used. How the voice carried the words - pitch
  movement, pace, emphasis - travels with them, and the answer is rendered in the same shade.
- Messages read as a conversation: what you sent on the right, what arrived on the left.
- Environment: outdoors, daylight, one hand, sometimes gloves, low battery, intermittent
  proximity to other devices.

## Capabilities and Constraints

Confirmed working today: end-to-end STT → translation → TTS → speaker playback on 11 languages
(English plus 10 Indic), message replay of cached audio, a "test voice" self-check that runs the
same path, and a 970 MB debug APK (clean `assembleDebug`, models included, nothing extracted).

Hard constraints, all measured on device:
- **One graph hears every language the mic claims.** The microphone is SraVaani-1.0 (ARTPARK +
  IISc, MIT): a 443.6M-parameter FastConformer-TDT covering 65 Indic languages, with no language
  token at all - so adding a language to the app cannot disable it. Its fp32 Conv weights were
  rewritten to per-channel int8 behind `DequantizeLinear` (638 → 477 MB) because ONNX Runtime for
  Android has no ConvInteger kernel. Measured with the `sttbench` scenario on the emulator against
  the shipped reference clips: 10 of 11 languages come back in their own script with every word of
  the reference present (Hindi, Kannada, Malayalam, Marathi byte-identical; Bengali, Gujarati,
  Punjabi, Tamil, Telugu, English differ only in spacing, punctuation or one matra), in 625-3,025 ms.
  **Odia is gated off and stays typed** - the model covers it, but there is no Odia clip in the pack
  to measure against, so it is not claimed. The interface marks which languages are live.
- **Translation is Indic↔Indic.** English is not a valid translation target; English-pair turns
  pass text through unchanged and say so. The decoder runs without a KV cache: the exported
  `decoder_with_past` graph asked for cross-attention caches the encoder never emits, so it could
  not have run, and 194 MB of it is gone. Cache-free greedy decode measures 287-577 ms per reply.
- **Synthesis quality is uneven.** Seven languages speak from a borrowed (donor) reference voice
  because their own reference clips fail load-time quality gates.
- **The install is 970 MB, and it holds nothing twice.** The multilingual mic was bought with the
  user's agreement at 850-900 MB (after deleting the Hindi-only Conformer, 131 MB; the unusable MT
  with-past export, 194 MB; and Whisper-base). A day later the ask was "under 1 GB total", because
  the 883 MB APK also extracted the 477 MB encoder into `filesDir` on first run - 1.36 GB per
  handset. That copy is gone: the encoder asset is stored uncompressed (`noCompress` on the
  `qdq.onnx` suffix only) and mapped straight out of base.apk, so the APK is 970 MB and the data
  dir is 8 KB. A handset that ran the older build has its stale extract deleted on next load.
  What the mapping costs is RAM instead of disk: ONNX Runtime copies roughly 2.1x a model's bytes
  into private native memory whatever route loads it, so the process sits at ~2.26 GB native after
  all four engines, and `System.gc()` returns none of it. The remaining size levers each cost
  something - int8 on the Vocos backbone (~-35 MB, timbre), int8 on the TDT joint decoder
  (~-18 MB, accuracy), arm64-only split (~-22 MB, drops the emulator build). Nothing may add large
  assets casually.
- **Speech starts in 3.4 s for a short reply and 6.0 s for a four-second one; under 3 s costs the
  solve's convergence.** Measured on the emulator, one demo turn: 10.2 s -> **6.0 s** render after
  (a) `ORT_THREADS` moved to 4 on a >=6-core device, which `debug fmcost` measured at 508 ms vs
  447 ms for a 241-frame step, and (b) Turbo cropping the donor prompt to 96 frames, which is what
  the arithmetic allows: cost = (prompt + generated frames) x steps x ~2.3 ms, and the prompt
  region rides through every step, so the full 241-frame clip is 3.3 s before one frame of speech
  exists. Six steps is the floor for a human contour - `debug latbench` at four steps reaches
  2.2 s but leaves prompt-reconstruction error at 0.85 against 0.70 and dynamics at 3.9 against
  the donor's 1.7. Full mode keeps the whole clip and its 16 steps. The three-seed A/B is
  contradictory and the ear decides: at 96 frames `Prosody.spread` is *tighter* (0.51-0.69 against
  0.43-2.48 at full length) while the host analyser counts more abrupt pitch jumps (44-62 against
  31-35) and one take at 177 Hz against the donor's 104.
- Android 12+ will not let an app silently enable Bluetooth or grant radio permissions; any
  "turns on automatically" behaviour is an honest, user-visible consent step, never a claim.
- **The male cut needs a standing trim.** The vocoder returns a solve brighter and a few Hz higher
  than the prompt that taught it - measured: Hindi male prompt 103 Hz with 0.7 % of energy above
  3 kHz, the Marathi reply it conditions 110 Hz with 1.9 %. That band is what a listener calls
  thin, harsh and robotic, so `Prosody.soften` walks the answer back toward its own prompt (now
  103 Hz, 1.3 %) rather than toward a guess about male speech. Off in Identity returns the raw solve.
- **Peer-to-peer delivery is unverified.** The radio is real BLE (advertise + scan + GATT links,
  fragmenting writes, forwarding with a hop limit) and it starts and reports its state truthfully,
  but no frame has yet crossed between two handsets. Until that is demonstrated, the interface
  must keep saying "not sent · no link up" rather than implying delivery.
- Two physical phones are required to verify the mesh; the emulator has no second radio.
- **Tone matching reads one axis, not feelings.** Pitch movement, speech density and emphasis
  separate an animated transmission from a flat one; whether a person is happy or angry does not,
  and guessing wrong is worse than saying nothing. So the interface names what was measured
  ("spoken animated 42%") rather than claiming an emotion, a turn too short to judge reads as
  neutral, and a neutral reading hands the model's own bytes back untouched - verified by md5, not
  asserted. Anchors are the lower quartile of the shipped reference pack, re-measured with the
  `prosody` debug scenario whenever that pack changes. It can be switched off in Identity.

Open and undecided: message encryption/group-key model, whether a three-or-more phone topology
needs more than the current hop-limited flood, and per-thread language of record.

## Brand Commitments

- Product name **VANI** (the launcher label). The earlier in-app title "iTantra Walkie" is being
  retired in favour of VANI.
- Language names must appear in their own script (हिन्दी, मराठी, தமிழ்), not only Latin transliteration.
- Explicit brief constraint: WhatsApp-style conversation reading — sent messages on the right in
  green, received on the left, large legible type — and a floating bottom navigation bar.
- **The visual system is the handed-off design, implemented as written.** Tokens come from
  `vani-app.css` (OKLCH converted in `ui/VaniTheme.kt`): near-black ground, one mint accent used at
  most twice a screen, a single dim-gold tone for pending/unverified, hairline borders, no gradients
  on surfaces, and every status carried by a word plus a shape. Type is Sora / Manrope / JetBrains
  Mono, bundled in `res/font` (612 KB), with system Noto resolving the Indic scripts. Screens are
  Mesh, Talk and Setup from the export's three HTML files.
- Four deliberate departures from the export, each because the app would otherwise lie or lose
  something the brief asks for:
  1. **A fourth nav cell, Demo.** The export has three tabs; the operator asked for the single-handset
     loopback bench by name, and it is how the chain is tested without a second phone.
  2. **The "Speech pack" switch is a readout.** The recognition model ships inside the APK, so a
     toggle would be a control that cannot do anything. The same facts appear, with real numbers.
  3. **No Rescan button.** This radio sweeps continuously; restarting the sweep would either do
     nothing visible or drop live GATT links. The sweep age is shown instead.
  4. **The tone line is a word, not a waveform.** The BLE frame carries one signed percent of
     animation, not an envelope, so drawing seven bars would be decoration dressed as measurement.
  5. **The language pickers are gone from Setup**, on the operator's instruction on 2026-09-27: all
     eleven languages ship loaded, so choosing which you "speak" gated nothing. The consequence is
     stated plainly here because it is a capability limit, not a cosmetic one - the live mesh pair is
     whatever the phone was last configured with (out of the box हिन्दी → मराठी) and there is no
     screen to change it. The bench still lets either side be set per test, and `ui.src`/`ui.tgt`
     persist across restarts.

## Evidence on Hand

- Working app source: `app/src/main/java/com/itantra/walkie/` (`MainActivity.kt` UI,
  `WalkieViewModel.kt` pipeline, `ml/` ONNX engines, `audio/` capture and playback).
- Reference voice clips and transcripts in `app/src/main/assets/refs/`.
- The wire codec in production on the mesh path: `assets/mesh/charmodel.bin` (447 symbols, order-1)
  through an LZMA range coder, framed with a CRC-16 and a flag that picks the smaller of coded or
  literal per message. Proven on the handset over all 22 shipped clips: 1069 B on air against
  1914 B of text, zero mismatches; 9 JVM round-trip/corruption/truncation tests in
  `app/src/test/java/com/itantra/walkie/net/WireCodecTest.kt`.
- Live console telemetry, all of it this process's own: bytes per message on the bubble, the
  draft's coded cost while it is typed, the session's on-air total, and CPU / resident memory read
  from `/proc/self`.
- Field survival layer, verified on the handset: a `connectedDevice` foreground service that keeps
  advertising and scanning with the app backgrounded (30 s after HOME, same pid, still
  `isForeground=true`); six one-tap rescue lines and an SOS beacon that repeats for a capped window
  and stops itself; a GPS position stamp shared to phones in range; and a loud-by-default media
  stream that yields permanently the first moment the user moves the volume.
- Proximity is stated as a band and a direction, never as metres. RSSI wobbles 10-20 dB on a wet
  hand, and 10 dB is a factor of ten in distance - so `calibrate` has to produce a real
  RSSI-against-ground-distance table on two handsets before any distance number is allowed on
  screen.
- Measured pipeline behaviour recorded in project memory (latency, per-language voice quality,
  playback continuity), and verification scripts in `scripts/`.
- **Absent, and must not be fabricated:** no user studies, no field deployments, no customer or
  government pilots, no benchmarks against other products, no testimonials, no usage statistics.

## Product Principles

1. **Works when nothing else does.** No network, no account, no server dependency — if a feature
   needs infrastructure, it does not belong in the core flow.
2. **Never overstate a language capability.** The stack is genuinely strong in some languages and
   weak in others; the interface states which is which instead of presenting a uniform promise.
3. **The mic is the product.** One primary action per screen, operable with one thumb, outdoors,
   with results that are heard as well as seen.
4. **People before radios.** The mesh is a means; what users care about is who is in range and
   whether the message reached them.
5. **Battery and bandwidth are scarce.** Every transmission and every continuous radio scan has a
   real cost in the field; the design should not encourage idle churn.

## Accessibility & Inclusion

Field use in daylight and at distance: high contrast, large type, and 48 dp minimum touch targets.
Multi-script text (Devanagari, Bengali, Gurmukhi, Gujarati, Odia, Tamil, Telugu, Kannada,
Malayalam) must render and scale correctly, including at elevated system font sizes. Status must
never rely on colour alone — a transmission state needs a word as well as a colour.
