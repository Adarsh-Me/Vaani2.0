# VANI

An on-device walkie-talkie for Indic field teams. Hold the mic, speak, release: the words are
recognised, translated and spoken back on the receiving handset in that operator's own language.
Every stage - recognition, translation, synthesis - runs inside the phone. There is no server, no
account, and nothing goes over a network; handsets find each other over Bluetooth LE.

11 languages (English plus 10 Indic). `arm64-v8a` and `x86_64`, Android 8.0+, debug-signed.

The product doc - what works, what is measured, what is honestly not yet true - is
[PRODUCT.md](PRODUCT.md). Read that before trusting any claim in a screenshot.

## Install

Grab `app-debug.apk` from the [latest release](../../releases) (970 MB, models included) and
install it on each handset. On first run it asks for a name and two languages, then starts
advertising. A second phone running VANI appears in the roster by itself - there is nothing to
pair.

Over USB:

```
adb install -r -d app-debug.apk
```

## What is not in this repository

**`app/src/main/assets/models/` (1,039 MB) is git-ignored, and it is not optional.** The app
bundles the microphone, the translator and the voice as seven ONNX graphs: an INT8 IndicTrans2
encoder and decoder (one model for every language pair, no KV-cache export), an INT8-QDQ SraVaani
FastConformer-TDT encoder with its joint decoder and NeMo fbank constants, and a split INT8
IndicF5 text-encoder / flow-matching decoder / Vocos backbone and head. Four of those files exceed
GitHub's 100 MB per-file limit, so they cannot be committed at all, and they are not something
`pip install` reproduces.

To build, you need that directory. Either take it from a working checkout, or re-export the MT and
TTS graphs from the upstream models listed in [`scripts/download_models.py`](scripts/download_models.py) -
that script names the Hugging Face repos and the export steps each artifact still needs. The
microphone needs no export, only a download and the quantise pass:

```
pip install torch onnx numpy sentencepiece
python scripts/fetch-sravaani.py     # -> 520 MB into assets/models/stt-sravaani/
```

Without the SraVaani files the app still builds and runs: the mic reports no voice for any
language and every turn is typed, rather than offering a dead microphone.

**Sizes are decimal MB throughout, and the install is over its original budget.** 800 MB was the
line until the microphone went multilingual; buying SraVaani instead of a Hindi-only engine moved
it to an agreed 850-900 MB. What paid for that: the MT `decoder_with_past` export (194 MB, and it
could never have run - `TranslatorEngine` decodes without a KV cache), the IndicConformer Hindi
bundle (131 MB) and Whisper-base (121 MB). See PRODUCT.md for the levers that are left and what
each one costs in audio quality.

A clean `assembleDebug` lands at **970 MB, and the data dir adds 8 KB**. The 477 MB encoder is the
reason the APK is stored rather than Deflated for that one entry: `androidResources.noCompress`
pins the `qdq.onnx` suffix so `Bundled.mapped()` can map the model out of base.apk instead of
extracting a second copy to `filesDir`, which is what an earlier build did and what put a handset
at 1.36 GB. Mapping costs RAM instead of disk - ONNX Runtime copies ~2.1x a model's bytes into
private native memory however it is loaded - so the process holds ~2.26 GB native with all four
engines up.

**The Gradle wrapper jar is also missing.** `gradle/wrapper/` holds only
`gradle-wrapper.properties`, and there is a `gradlew.bat` but no POSIX `gradlew`. On Windows
`gradlew.bat` resolves a local Gradle 7.5 or downloads 8.7 into `%TEMP%`; elsewhere, install
Gradle 8.x and run `gradle :app:assembleDebug`.

## Build

```
gradlew.bat assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk
```

Nothing is downloaded at build time beyond Gradle itself; every model is read out of the installed
APK at runtime - six as buffers, the 477 MB encoder as a memory map - so the APK is the whole
product.

## Layout

```
app/src/main/java/com/itantra/walkie/
  ml/       the three engines, the reference-voice pack, prosody, DSP, wire codec
  audio/    microphone capture and playback
  net/      BLE mesh: advertising, scanning, GATT links, fragmentation, relaying
  ui/       Mesh / Talk / Setup / Demo - Compose, one theme from the handed-off design system
  WalkieViewModel.kt   the one place a turn is assembled
scripts/    host-side measurement: pitch, spectrum, WER, reference-voice audit
```

## Measuring instead of guessing

The app can drive its own pipeline from adb, which is how every number in this project was
obtained. Start it with a scenario name:

```
adb shell am start -n com.itantra.walkie/.MainActivity --es debug <scenario>
adb logcat -s BENCH USER TONE PROSODY REFS LOAD STT MTREBASE
```

| scenario | what it answers |
|---|---|
| `bench` | per-stage latency for short, medium and long sentences |
| `tone` | whether the tone render is byte-identical when neutral, and what it does at the extremes |
| `toneab` | one line played flat, animated, subdued - the emotion heard without a second phone |
| `prosody` | the pitch-movement and pace anchors, measured over every reference clip |
| `pick`, `pick32` | whether a deeper flow solve or two short candidates wins |
| `ttsall`, `mtall` | all 11 languages end to end, with the reference each one actually used |
| `wer` | word error rate over `wer_*.wav` clips dropped into filesDir |
| `sttbench` | mic coverage per language, and the reference clip each one is actually recognised into |
| `mtrebase` | whether the Devanagari-only MT dictionary can read a re-based script, per language |
| `demo` | one turn around the single-handset loopback bench |

The **Demo** pane in the app is the same thing with a screen on it: phone 1 and phone 2 on one
handset, each with its own language, so the whole chain can be tested with no second device in
range.
