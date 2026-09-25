# VANI

An on-device walkie-talkie for Indic field teams. Hold the mic, speak, release: the words are
recognised, translated and spoken back on the receiving handset in that operator's own language.
Every stage - recognition, translation, synthesis - runs inside the phone. There is no server, no
account, and nothing goes over a network; handsets find each other over Bluetooth LE.

11 languages (English plus 10 Indic). `arm64-v8a` and `x86_64`, Android 8.0+, debug-signed.

The product doc - what works, what is measured, what is honestly not yet true - is
[PRODUCT.md](PRODUCT.md). Read that before trusting any claim in a screenshot.

## Install

Grab `app-debug.apk` from the [latest release](../../releases) (~686 MB, models included) and
install it on each handset. On first run it asks for a name and two languages, then starts
advertising. A second phone running VANI appears in the roster by itself - there is nothing to
pair.

Over USB:

```
adb install -r -d app-debug.apk
```

## What is not in this repository

**`app/src/main/assets/models/` (812 MB) is git-ignored, and it is not optional.** The app bundles
three custom ONNX exports - an INT8 IndicTrans2 graph, an INT8 IndicConformer CTC branch, and a
split INT8 IndicF5 text-encoder / flow-matching decoder / Vocos head. Five of those files exceed
GitHub's 100 MB per-file limit, so they cannot be committed at all, and they are not something
`pip install` reproduces.

To build, you need that directory. Either take it from a working checkout, or re-export it from the
upstream models listed in [`scripts/download_models.py`](scripts/download_models.py) - that script
names the three Hugging Face repos and the export steps each artifact still needs.

The release APK carries the whole pack inside its own assets, which is why it is 686 MB.

**The Gradle wrapper jar is also missing.** `gradle/wrapper/` holds only
`gradle-wrapper.properties`, and there is a `gradlew.bat` but no POSIX `gradlew`. On Windows
`gradlew.bat` resolves a local Gradle 7.5 or downloads 8.7 into `%TEMP%`; elsewhere, install
Gradle 8.x and run `gradle :app:assembleDebug`.

## Build

```
gradlew.bat assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk
```

Nothing is downloaded at build time beyond Gradle itself; the models are read straight out of the
installed APK at runtime, so the APK is the whole product.

## Layout

```
app/src/main/java/com/itantra/walkie/
  ml/       the three engines, the reference-voice pack, prosody, DSP, wire codec
  audio/    microphone capture and playback
  net/      BLE mesh: advertising, scanning, GATT links, fragmentation, relaying
  ui/       the dispatcher console - Compose, one theme, four panes
  WalkieViewModel.kt   the one place a turn is assembled
scripts/    host-side measurement: pitch, spectrum, WER, reference-voice audit
```

## Measuring instead of guessing

The app can drive its own pipeline from adb, which is how every number in this project was
obtained. Start it with a scenario name:

```
adb shell am start -n com.itantra.walkie/.MainActivity --es debug <scenario>
adb logcat -s BENCH USER TONE PROSODY REFS LOAD
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
| `demo` | one turn around the single-handset loopback bench |

The **Demo** pane in the app is the same thing with a screen on it: phone 1 and phone 2 on one
handset, each with its own language, so the whole chain can be tested with no second device in
range.
