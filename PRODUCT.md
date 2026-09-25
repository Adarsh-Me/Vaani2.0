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
same path, and a 686 MB APK.

Hard constraints, all measured on device:
- **Speech recognition exists for Hindi only.** Other languages must be typed, or spoken Hindi
  translated outward. This must never be implied otherwise in the interface.
- **Translation is Indic↔Indic.** English is not a valid translation target; English-pair turns
  pass text through unchanged and say so.
- **Synthesis quality is uneven.** Seven languages speak from a borrowed (donor) reference voice
  because their own reference clips fail load-time quality gates.
- The install must stay under 800 MB; nothing may add large assets casually.
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

## Evidence on Hand

- Working app source: `app/src/main/java/com/itantra/walkie/` (`MainActivity.kt` UI,
  `WalkieViewModel.kt` pipeline, `ml/` ONNX engines, `audio/` capture and playback).
- Reference voice clips and transcripts in `app/src/main/assets/refs/`.
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
