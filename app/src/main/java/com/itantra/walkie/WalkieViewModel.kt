package com.itantra.walkie

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.onnxruntime.OrtEnvironment
import com.itantra.walkie.audio.MicRecorder
import com.itantra.walkie.audio.Player
import com.itantra.walkie.ml.ConformerStt
import com.itantra.walkie.ml.DhVaaniEngine
import com.itantra.walkie.ml.ModelInstaller
import com.itantra.walkie.ml.TranslatorEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One chat bubble. `outgoing` means this phone produced it; the far end gets the translation. */
data class Msg(
    val id: Long,
    val outgoing: Boolean,
    val text: String,
    val note: String = "",
    val speaking: Boolean = false,
    /** True once this line's TTS audio is cached and can be replayed. */
    val hasAudio: Boolean = false,
    /** Wall clock at the moment the line entered the thread, for the bubble's timestamp. */
    val atMs: Long = System.currentTimeMillis(),
    /**
     * How the voice behind this line sounded, read off the sender's own microphone: -1 flat,
     * +1 animated, 0 when nothing was read - a typed line, or a turn too short to judge.
     */
    val tone: Float = 0f,
)

/** Which of the console's four panes is lit. */
enum class Pane { Channels, OnAir, Identity, Demo }

/**
 * The single-handset loopback bench: two handsets simulated on this phone, each with its own
 * language, so the whole capture -> recognise -> translate -> speak chain can be tested without a
 * second handset in range. The conversation itself is not held here - it lives in
 * [UiState.threads] under [WalkieViewModel.DEMO_TX] and [WalkieViewModel.DEMO_RX], the same way
 * a channel's does, so the bench reads the real thread state rather than a copy of it.
 */
data class DemoState(
    val txLang: Lang = Lang.HI,
    val rxLang: Lang = Lang.MR,
    val rxVoice: Voice = Voice.M,
    /** Stage split of the last turn that crossed, in seconds. */
    val wire: String = "",
    /** How that turn was answered, in the words the bench shows next to the pair. */
    val toneLabel: String = "",
    /** A turn is being translated and solved right now. */
    val running: Boolean = false,
)

data class UiState(
    val src: Lang = Lang.HI, val tgt: Lang = Lang.MR,
    val voice: Voice = Voice.M,
    val translateOn: Boolean = true,
    val turbo: Boolean = true,
    /** Speak the reply in the tone the caller's voice arrived with. Off hands back the raw solve. */
    val matchTone: Boolean = true,
    /** The standing trim that takes the edge off the male cut. Off is the raw solve. */
    val softMale: Boolean = true,
    val status: String = "idle",
    val lastText: String = "",
    val lastTranslated: String = "",
    /** Display name this phone advertises into the mesh, so others recognise it in their scan. */
    val name: String = "",
    /** Languages the operator says they speak, picked at setup; the mic language drives STT. */
    val spoken: List<Lang> = emptyList(),
    val setupDone: Boolean = false,
    /** Which channel is lit: [Address.ALL_ID] or a peer id. */
    val active: String = com.itantra.walkie.net.Address.ALL_ID,
    /** Which of the console's panes is on screen. */
    val pane: Pane = Pane.Channels,
    /** Per-channel bubbles, newest last. Keyed by [Address.ALL_ID] or a peer's address. */
    val threads: Map<String, List<Msg>> = emptyMap(),
    val demo: DemoState = DemoState(),
)

class WalkieViewModel(app: Application) : AndroidViewModel(app) {
    var ui by mutableStateOf(UiState()); private set

    companion object {
        /**
         * Thread keys for the loopback bench. They are not mesh addresses: nothing written here
         * can reach the radio, and the names keep that visible in a log or a bug report.
         */
        const val DEMO_TX = "DEMO-1"
        const val DEMO_RX = "DEMO-2"
    }

    private val env = OrtEnvironment.getEnvironment()
    /** Every model and reference clip is read straight out of the installed APK. */
    private val bundled by lazy { com.itantra.walkie.ml.Bundled(getApplication()) }
    private var stt: ConformerStt? = null
    private var mt: TranslatorEngine? = null
    private var tts: DhVaaniEngine? = null
    private val mic = MicRecorder()
    private val player by lazy { Player(app.cacheDir) }

    private fun updateUi(s: UiState) { ui = s }

    // ---------------------------------------------------------------- mesh + identity

    private val prefs by lazy {
        getApplication<Application>().getSharedPreferences("vani", android.content.Context.MODE_PRIVATE)
    }

    /** The radio. Everything the console knows about the mesh arrives through it. */
    val mesh: com.itantra.walkie.net.MeshTransport by lazy {
        com.itantra.walkie.net.BleMesh(getApplication()) { ui.name to ui.src }.also { m ->
            m.listener = { from, channel, text, tone -> onInbound(from, channel, text, tone) }
        }
    }

    /**
     * A frame off the air. The wire carries the sender's own language and the shape of their
     * voice, so the receiving phone - this one - does the translation, the speaking and the
     * matching. That is what lets one transmission serve every operator in the mesh without any
     * of them knowing each other's language.
     */
    private fun onInbound(
        fromPeerId: String, channelId: String, text: String, tone: com.itantra.walkie.ml.Tone
    ) {
        // A frame that arrived is a frame that arrived: it goes on its channel even if the roster
        // has not re-listed that node this second, and an unknown sender is labelled as one
        // rather than silently dropped.
        val peer = mesh.lastKnown(fromPeerId)
        val from = peer?.name?.takeIf { it.isNotBlank() } ?: "unknown node"
        runReceive(
            text = text,
            fromLang = peer?.lang ?: ui.tgt,
            toLang = ui.tgt,
            voice = ui.voice,
            channelId = channelId,
            tag = from,
            note = "$from · said: $text",
            tone = tone,
        )
    }

    /**
     * The receive half of a turn: translate what arrived into this handset's language, speak it
     * in the tone that arrived with it, and cache the audio so the line can be replayed. The mesh
     * path and the loopback bench both come through here, which is what makes a bench turn a
     * real test of the code an inbound frame runs rather than a simulation of it.
     */
    private fun runReceive(
        text: String,
        fromLang: Lang,
        toLang: Lang,
        voice: Voice,
        channelId: String,
        tag: String,
        note: String,
        tone: com.itantra.walkie.ml.Tone = com.itantra.walkie.ml.Tone.NEUTRAL,
        onComplete: (String) -> Unit = { },
    ) {
        viewModelScope.launch(Dispatchers.Default) {
            val t0 = System.currentTimeMillis()
            val (tr, _) = translateTurn(text, fromLang, toLang)
            val mtMs = System.currentTimeMillis() - t0
            val id = ++msgSeq
            withContext(Dispatchers.Main) {
                appendToThread(channelId, Msg(id, false, tr, note + toneMark(tone), tone = tone.animation))
                setSpeaking(id, true)
                updateUi(ui.copy(lastText = text, lastTranslated = tr, status = "incoming · $tag"))
            }
            val sp = speakReply(tr, toLang, voice, tone)
            cacheAudio(id, sp.pcm)
            android.util.Log.e(
                "USER", "rx mt=${mtMs}ms render=${sp.synthMs}ms played=${sp.playedMs}ms " +
                    "tone=${(tone.animation * 100).toInt()}% err=${sp.err} ${ttsStages()}"
            )
            val timing = "mt ${sec(mtMs)} · voice ${sec(sp.synthMs)} · " +
                "audio ${String.format("%.1f", sp.pcm.size / AppConfig.TTS_SR.toDouble())}s" +
                when {
                    sp.err.isNotBlank() -> " · ${sp.err}"
                    // A turn that produced no audio has to say so: a silent 0.0s reads as a
                    // working engine that simply had nothing to say.
                    sp.pcm.isEmpty() -> " · nothing spoken"
                    else -> ""
                }
            withContext(Dispatchers.Main) {
                editInThread(channelId, id) {
                    it.copy(hasAudio = sp.pcm.isNotEmpty(), speaking = false)
                }
                updateUi(ui.copy(
                    status = "translated ${sec(mtMs)} · voice ${sec(sp.synthMs)} ${sp.err}"
                ))
                onComplete(timing)
            }
        }
    }

    private fun appendToThread(channelId: String, m: Msg) {
        val cur = ui.threads[channelId] ?: emptyList()
        updateUi(ui.copy(threads = ui.threads + (channelId to (cur + m))))
    }

    private fun editInThread(channelId: String, id: Long, patch: (Msg) -> Msg) {
        val cur = ui.threads[channelId] ?: return
        updateUi(ui.copy(threads = ui.threads + (channelId to cur.map { if (it.id == id) patch(it) else it })))
    }

    /** Ask the radio to come up and start a sweep. Safe to call on every return to the foreground. */
    fun bringUpMesh() = mesh.start()

    fun selectChannel(peerId: String) { updateUi(ui.copy(active = peerId)) }

    fun selectPane(p: Pane) { updateUi(ui.copy(pane = p)) }

    /** What the lit channel is called, in the strip and on the talk bar. */
    fun channelName(): String = when (ui.active) {
        com.itantra.walkie.net.Address.ALL_ID -> "ALL · broadcast"
        else -> peerKnown(ui.active)?.name?.takeIf { it.isNotBlank() } ?: ui.active
    }

    /** Three engines have to be resident before any stage of a turn can run. */
    fun ready(): Boolean = stt?.isReady() == true && mt?.isReady() == true && tts?.isReady() == true

    fun peerById(peerId: String): com.itantra.walkie.net.Peer? = mesh.peers.firstOrNull { it.id == peerId }

    /** The last known record of a node, for a channel whose device has drifted out of range. */
    fun peerKnown(peerId: String): com.itantra.walkie.net.Peer? =
        peerById(peerId) ?: mesh.lastKnown(peerId)

    /** True when the lit channel has a live link that a transmission could travel on. */
    fun channelDeliverable(): Boolean =
        mesh.reachable(com.itantra.walkie.net.Address.of(ui.active))

    /**
     * Speech recognition exists for Hindi only, so the mic is real for exactly one of the
     * languages an operator says they speak. The interface has to say so rather than offer a
     * dead microphone.
     */
    fun micUsable(): Boolean = ui.src == Lang.HI && stt?.isReady() == true

    /** Called from setup: identity is what peers see, and it survives restarts. */
    fun completeSetup(name: String, spoken: List<Lang>, micIn: Lang, hearIn: Lang) {
        val n = name.trim().ifBlank { "This phone" }
        with(prefs.edit()) {
            putString("name", n); putBoolean("setup", true)
            putString("spoken", spoken.joinToString(",") { it.name })
            putString("src", micIn.name)
            putString("tgt", hearIn.name)
        }.apply()
        updateUi(ui.copy(name = n, spoken = spoken, setupDone = true, src = micIn, tgt = hearIn))
        bringUpMesh()
    }

    private fun restoreIdentity() {
        val done = prefs.getBoolean("setup", false)
        if (!done) return
        val spoken = (prefs.getString("spoken", "HI") ?: "HI").split(",").mapNotNull {
            runCatching { Lang.valueOf(it.trim()) }.getOrNull()
        }.ifEmpty { listOf(Lang.HI) }
        val src = runCatching { Lang.valueOf(prefs.getString("src", "HI") ?: "HI") }.getOrDefault(Lang.HI)
        val tgt = runCatching { Lang.valueOf(prefs.getString("tgt", "MR") ?: "MR") }.getOrDefault(Lang.MR)
        updateUi(ui.copy(
            name = prefs.getString("name", "This phone") ?: "This phone",
            spoken = spoken, setupDone = true, src = src, tgt = tgt
        ))
    }

    /**
     * Send on the lit channel. The wire carries the source text and the shape of the voice that
     * said it, never a translation: each receiving phone turns it into its own operator's language
     * locally and speaks it back in the caller's tone, which is what lets one transmission serve
     * every handset in range and keeps the frame small enough for Bluetooth.
     */
    fun sendMessage(text: String, tone: com.itantra.walkie.ml.Tone = com.itantra.walkie.ml.Tone.NEUTRAL) {
        val t = text.trim()
        if (t.isEmpty()) return
        val chan = ui.active
        val nodes = mesh.nodesReached
        val sent = mesh.send(com.itantra.walkie.net.Address.of(chan), t, tone)
        appendToThread(chan, Msg(
            ++msgSeq, true, t,
            (if (sent) "on air · $nodes node${if (nodes == 1) "" else "s"} in range"
            else "not sent · no phone in range") + toneMark(tone),
            tone = tone.animation
        ))
        updateUi(ui.copy(
            lastText = t,
            status = if (sent) "sent over the air" else "not sent · no link up"
        ))
    }

    /**
     * Press-and-hold on the talk bar opens the microphone; releasing runs
     * capture -> recognise -> transmit on the lit channel. Nothing is spoken back on this phone:
     * the handset that receives is the one that translates and talks, which is the whole point of
     * putting the model on every device instead of on a server.
     */
    fun startTalk() {
        mic.start()
        updateUi(ui.copy(status = "on air"))
    }

    fun finishTalk() {
        viewModelScope.launch(Dispatchers.Default) {
            recognise()?.let { withContext(Dispatchers.Main) { sendMessage(it.text, it.tone) } }
        }
    }

    /** Words off the microphone, and how they were said. */
    private data class Said(val text: String, val tone: com.itantra.walkie.ml.Tone)

    /**
     * Closes the capture and turns it into words, reporting every failure the way the console
     * says it. The tone is read from the same bytes the recogniser sees, before it: how someone
     * spoke is still there even when the words come back mangled. Returns null when nothing
     * usable came off the microphone; the caller decides where the words go, because the air and
     * the bench route them differently.
     */
    private suspend fun recognise(): Said? {
        withContext(Dispatchers.Main) { updateUi(ui.copy(status = "decoding…")) }
        val pcm = mic.stop()
        val lvl = peak(pcm)
        if (pcm.isEmpty() || lvl < 0.01f) {
            withContext(Dispatchers.Main) {
                updateUi(ui.copy(status = "the mic heard nothing · check the input device"))
            }
            return null
        }
        val read = com.itantra.walkie.ml.Prosody.measure(pcm, AppConfig.STT_SR)
        val tone = com.itantra.walkie.ml.Tone(read.animation())
        android.util.Log.e(
            "PROSODY", "read spread=${"%.2f".format(read.spread)} density=${"%.2f".format(read.density)} " +
                "dyn=${"%.2f".format(read.dynamics)} voiced=${read.voiced} usable=${read.usable} " +
                "tone=${(tone.animation * 100).toInt()}%"
        )
        val t0 = System.currentTimeMillis()
        val raw = stt?.transcribe(pcm)?.text ?: ""
        val ms = System.currentTimeMillis() - t0
        if (raw.isBlank()) {
            withContext(Dispatchers.Main) {
                updateUi(ui.copy(status = "nothing recognised · ${stt?.lastError ?: ""}"))
            }
            return null
        }
        android.util.Log.e("USER", "talk stt=${ms}ms tone=${(tone.animation * 100).toInt()}% heard='$raw'")
        return Said(raw, tone)
    }

    /** The bubbles for one channel. */
    fun threadFor(peerId: String): List<Msg> = ui.threads[peerId] ?: emptyList()

    // ------------------------------------------------------------------ loopback bench

    /**
     * One handset playing both ends. Phone 1's words are put on phone 2's thread exactly as an
     * inbound frame is put on a channel's: same translate, same solve, same voice, same cache -
     * only the Bluetooth is skipped, and the line says so rather than pretending it travelled.
     */
    fun demoSend(
        text: String, tone: com.itantra.walkie.ml.Tone = com.itantra.walkie.ml.Tone.NEUTRAL
    ) {
        val t = text.trim()
        if (t.isEmpty()) return
        val d = ui.demo
        appendToThread(DEMO_TX, Msg(
            ++msgSeq, true, t,
            "phone 1 · ${d.txLang.native} · loopback, not on the air" + toneMark(tone),
            tone = tone.animation
        ))
        updateUi(ui.copy(
            lastText = t,
            demo = d.copy(running = true, wire = "recognised · translating ${d.txLang.label} → ${d.rxLang.label}…"),
            status = "on the bench · phone 1 → phone 2"
        ))
        runReceive(
            text = t,
            fromLang = d.txLang,
            toLang = d.rxLang,
            voice = d.rxVoice,
            channelId = DEMO_RX,
            tag = "phone 1",
            note = "phone 1 · said: $t",
            tone = tone,
            onComplete = { timing ->
                updateUi(ui.copy(demo = ui.demo.copy(
                    running = false,
                    wire = timing,
                    toneLabel = when {
                        !ui.matchTone -> "tone matching off"
                        tone.neutral -> "no tone read"
                        else -> "spoken ${toneWord(tone.animation)} ${(tone.animation * 100).toInt()}%"
                    }
                )))
            },
        )
    }

    fun demoStartTalk() {
        mic.start()
        updateUi(ui.copy(status = "on air · phone 1"))
    }

    fun demoFinishTalk() {
        viewModelScope.launch(Dispatchers.Default) {
            recognise()?.let { withContext(Dispatchers.Main) { demoSend(it.text, it.tone) } }
        }
    }

    /** Recognition exists for Hindi only, so the bench's microphone is real for one language. */
    fun demoMicUsable(): Boolean = ui.demo.txLang == Lang.HI && stt?.isReady() == true

    /** A sentence already written in the language phone 1 is set to, for a turn without typing. */
    fun demoSample(): String = ttsTestSentence(ui.demo.txLang)

    /**
     * Plays the last line phone 2 heard three ways - as it was solved, animated, subdued - so the
     * tone can be heard on one handset, on demand, without anyone emoting into a microphone or a
     * second phone being in range. It replays the cached audio rather than re-solving: this is a
     * listening test of the render, not another measurement of the model.
     */
    fun demoHearTone() {
        val line = threadFor(DEMO_RX).lastOrNull { it.hasAudio }
        val pcm = line?.id?.let { cachedAudio(it) }
        if (pcm == null || pcm.isEmpty()) {
            setStatus("nothing on phone 2 to replay · send a line first")
            return
        }
        viewModelScope.launch(Dispatchers.Default) {
            for ((label, a) in listOf("as solved" to 0f, "animated" to 0.75f, "subdued" to -0.75f)) {
                val out = com.itantra.walkie.ml.Prosody.paint(
                    pcm, AppConfig.TTS_SR, com.itantra.walkie.ml.Tone(a)
                )
                withContext(Dispatchers.Main) { setStatus("hearing phone 2 · $label") }
                player.play(out)
                delay(450)
            }
            withContext(Dispatchers.Main) { setStatus("tone A/B done · three readings of one line") }
        }
    }

    fun demoClear() {
        updateUi(ui.copy(
            threads = ui.threads - DEMO_TX - DEMO_RX,
            demo = ui.demo.copy(running = false, wire = "", toneLabel = ""),
            status = "bench cleared",
        ))
    }

    fun setDemoTxLang(l: Lang) = setDemo(ui.demo.copy(txLang = l))

    fun setDemoRxLang(l: Lang) = setDemo(ui.demo.copy(rxLang = l))

    fun setDemoRxVoice(v: Voice) = setDemo(ui.demo.copy(rxVoice = v))

    /**
     * Turns the bench around, which is how the other direction of a pair gets tested. Only the
     * two languages move: the threads stay where they are, so a line's note keeps naming the
     * handset that actually said it.
     */
    fun swapDemo() {
        val d = ui.demo
        val turned = d.copy(
            txLang = d.rxLang, rxLang = d.txLang, wire = "bench turned around", toneLabel = ""
        )
        setDemo(turned)
        updateUi(ui.copy(
            status = "phone 1 now speaks ${turned.txLang.label}, phone 2 hears ${turned.rxLang.label}"
        ))
    }

    private fun setDemo(d: DemoState) {
        updateUi(ui.copy(demo = d))
        with(prefs.edit()) {
            putString("demo_src", d.txLang.name)
            putString("demo_tgt", d.rxLang.name)
            putString("demo_voice", d.rxVoice.name)
        }.apply()
    }

    private fun restoreDemo() {
        val d = ui.demo.copy(
            txLang = runCatching { Lang.valueOf(prefs.getString("demo_src", "HI") ?: "HI") }
                .getOrDefault(Lang.HI),
            rxLang = runCatching { Lang.valueOf(prefs.getString("demo_tgt", "MR") ?: "MR") }
                .getOrDefault(Lang.MR),
            rxVoice = runCatching { Voice.valueOf(prefs.getString("demo_voice", "M") ?: "M") }
                .getOrDefault(Voice.M),
        )
        updateUi(ui.copy(demo = d))
    }
    private var msgSeq = 0L

    /** Per-line TTS audio for the replay button. Capped so long chats can't OOM. */
    private val audioCache = LinkedHashMap<Long, FloatArray>()
    private val audioLock = Any()
    private fun cacheAudio(id: Long, pcm: FloatArray) {
        if (pcm.isEmpty()) return
        synchronized(audioLock) {
            audioCache[id] = pcm
            while (audioCache.size > 25) {
                val eldest = audioCache.keys.firstOrNull() ?: break
                audioCache.remove(eldest)
            }
        }
    }
    private fun cachedAudio(id: Long): FloatArray? = synchronized(audioLock) { audioCache[id] }

    /** Flip the speaking mark on one line, whichever channel it sits on. Callers are on Main. */
    private fun setSpeaking(id: Long, speaking: Boolean) {
        val map = ui.threads.mapValues { (_, list) ->
            list.map { if (it.id == id) it.copy(speaking = speaking) else it }
        }
        updateUi(ui.copy(threads = map))
    }

    private fun peak(pcm: FloatArray): Float {
        var p = 0f
        for (v in pcm) { val a = kotlin.math.abs(v); if (a > p) p = a }
        return p
    }

    fun initModels(debug: String? = null) {
        restoreIdentity()
        restoreDemo()
        viewModelScope.launch(Dispatchers.Default) {
            withContext(Dispatchers.Main) { updateUi(ui.copy(status = "loading models…")) }
            val t0 = System.currentTimeMillis()
            ModelInstaller.ensure(getApplication()) // clears the pre-streaming filesDir copy
            val res = bundled
            var sttErr = ""
            mt = TranslatorEngine(env, res, AppConfig.ORT_THREADS)
            val tMt = System.currentTimeMillis()
            val s = ConformerStt(env, res, AppConfig.ORT_THREADS)
            sttErr = s.lastError
            stt = s
            val tStt = System.currentTimeMillis()
            val e = DhVaaniEngine(env, res, AppConfig.ORT_THREADS)
            val tTts = System.currentTimeMillis()
            e.preloadFromDir { it.name }
            tts = e
            android.util.Log.e("LOAD", "mt=${tMt - t0}ms stt=${tStt - tMt}ms tts=${tTts - tStt}ms " +
                "refs=${System.currentTimeMillis() - tTts}ms total=${System.currentTimeMillis() - t0}ms")
            val secs = String.format("%.1f", (System.currentTimeMillis() - t0) / 1000.0)
            val msg = if (sttErr.isBlank() && stt?.isReady() == true && mt?.isReady() == true &&
                tts?.isReady() == true
            ) "engines ready · ${tts?.refCount()} voices · up in ${secs}s"
            else "an engine failed to load · $sttErr"
            withContext(Dispatchers.Main) { updateUi(ui.copy(status = msg)) }
            when (debug) {
                "bench" -> benchmark()
                "probe" -> probe()
                "diag" -> diagnose()
                "ttsall" -> ttsAllLangs()
                "mtall" -> mtAllLangs()
                "mtconv" -> mtConventions()
                "sweep" -> sweepSampling()
                "seeds" -> seedExperiment()
                "xmatrix" -> crossMatrix()
                "pick" -> pickValidation()
                "pick32" -> pickValidation32()
                "fmcost" -> fmCostProbe()
                "refprobe" -> refProbe()
                "wer" -> werBenchmark()
                "ab" -> promptAB()
                // What the tone anchors are calibrated against: the shipped reference clips read
                // as if their speakers had just transmitted.
                "prosody" -> prosodyAudit()
                // The two proofs the tone feature needs without a second handset in range: a
                // neutral reading hands back the model's own bytes, and a painted one moves by
                // the percentage it claims to.
                "tone" -> toneRender()
                // The audible proof, on one handset and without anyone emoting for it: the same
                // solved sentence played flat, then animated, then subdued.
                "toneab" -> toneAudible()
                // adb-only harness for the outbound path on one phone: it proves capture,
                // recognition and framing, and it reports "not sent" honestly when no second
                // handset is in range. The receive path is verified between two real phones.
                "transmit" -> withContext(Dispatchers.Main) {
                    sendMessage("नमस्कार, सभी टीमों को सूचित किया जाता है।")
                }
                // One turn around the loopback bench, from adb, with no second handset and no
                // microphone: it exercises the same translate -> solve -> speak path an inbound
                // frame runs, so a language pair can be smoke-tested on a single device.
                "demo" -> withContext(Dispatchers.Main) { demoSend(demoSample()) }
            }
        }
    }

    /** One fixed natural sentence per language for the all-language TTS baseline. */
    private fun ttsTestSentence(lg: Lang): String = when (lg) {
        Lang.EN -> "Hello, how are you? I am fine."
        Lang.HI -> "नमस्कार, आप कैसे हैं? मैं ठीक हूँ।"
        Lang.BN -> "নমস্কার, আপনি কেমন আছেন? আমি ভালো আছি।"
        Lang.GU -> "નમસ્તે, તમે કેમ છો? હું મજામાં છું."
        Lang.KN -> "ನಮಸ್ಕಾರ, ನೀವು ಹೇಗಿದ್ದೀರಿ? ನಾನು ಚೆನ್ನಾಗಿದ್ದೇನೆ."
        Lang.ML -> "നമസ്കാരം, സുഖമാണോ? എനിക്ക് സുഖമാണ്."
        Lang.MR -> "नमस्कार, तुम्ही कसे आहात? मी ठीक आहे."
        Lang.PA -> "ਸਤਿ ਸ੍ਰੀ ਅਕਾਲ, ਤੁਸੀਂ ਕਿਵੇਂ ਹੋ? ਮੈਂ ਠੀਕ ਹਾਂ।"
        Lang.TA -> "வணக்கம், நீங்கள் எப்படி இருக்கிறீர்கள்? நான் நன்றாக இருக்கிறேன்."
        Lang.TE -> "నమస్కారం, మీరు ఎలా ఉన్నారు? నేను బాగున్నాను."
        Lang.OR -> "ନମସ୍କାର, ଆପଣ କେମିତି ଅଛନ୍ତି? ମୁଁ ଭଲ ଅଛି।"
    }

    /**
     * Phase-0 baseline: synthesize the same greeting in all 11 languages x M/F,
     * save each wav to filesDir for adb-pull + offline spectral/pitch analysis.
     * No AudioTrack involved, so the emulator sink can't pollute the measurement.
     */
    private fun ttsAllLangs() {
        viewModelScope.launch(Dispatchers.Default) {
            val base = getApplication<Application>().filesDir
            withContext(Dispatchers.Main) { updateUi(ui.copy(status = "ttsall running…")) }
            android.util.Log.e("TTSALL", "refs ${tts?.refInfo()}")
            for (lg in Lang.values()) {
                for (v in arrayOf(Voice.M, Voice.F)) {
                    val t0 = System.currentTimeMillis()
                    val pcm = tts?.synthesize(ttsTestSentence(lg), lg, v, AppConfig.TTS_NFE_FULL)
                        ?: FloatArray(0)
                    val ms = System.currentTimeMillis() - t0
                    val e = tts
                    try {
                        if (pcm.isNotEmpty()) com.itantra.walkie.ml.WavIO.write16(
                            File(base, "tts_all_${lg.name}_${v.name}.wav"), AppConfig.TTS_SR, pcm
                        )
                    } catch (_: Exception) {}
                    var pk = 0f; var sq = 0.0
                    for (s in pcm) { val a = if (s < 0) -s else s; if (a > pk) pk = a; sq += s.toDouble() * s }
                    val rms = if (pcm.isEmpty()) 0.0 else Math.sqrt(sq / pcm.size)
                    android.util.Log.e(
                        "TTSALL",
                        "lg=${lg.name} voice=$v ref=${e?.refSource(lg, v)} n=${pcm.size} " +
                            "frames=${e?.lastFrames} ms=$ms peak=${"%.3f".format(pk)} " +
                            "rms=${"%.4f".format(rms)} voc=[${e?.lastVocShape}] mel=[${e?.lastMelStats}] err=${e?.lastError}"
                    )
                }
            }
            withContext(Dispatchers.Main) { updateUi(ui.copy(status = "ttsall done")) }
        }
    }

    /**
     * Which language-token layout makes IndicTrans2 commit to the target script?
     * Bengali/Tamil/English are unambiguous tests because their script differs from
     * the Hindi source; a convention that answers in Devanagari is just paraphrasing.
     */
    private fun mtConventions() {
        viewModelScope.launch(Dispatchers.Default) {
            withContext(Dispatchers.Main) { updateUi(ui.copy(status = "mt conventions…")) }
            val m = mt ?: return@launch
            val hi = "मुझे आपके साथ बात करके अच्छा लगा।"
            for (tgt in listOf(Lang.BN, Lang.TA, Lang.EN, Lang.MR)) {
                for (conv in 1..6) {
                    val t0 = System.currentTimeMillis()
                    val out = m.translateWith(hi, Lang.HI, tgt, conv)
                    android.util.Log.e("MTCONV", "tgt=${tgt.tag} conv=$conv ms=${System.currentTimeMillis() - t0} out=$out")
                }
            }
            withContext(Dispatchers.Main) { updateUi(ui.copy(status = "mt conventions done")) }
        }
    }

    /**
     * Whole-fleet check of the real pipeline per language: MT Hindi->X, then TTS of that
     * translation. translate() returns the input unchanged when it fails, so `same=true`
     * means the pair is broken; wavs land in filesDir as tts_q_{lang}.wav for host-side
     * fluency metrics (silence fraction, pitch jumps, duration).
     */
    private fun mtAllLangs() {
        viewModelScope.launch(Dispatchers.Default) {
            withContext(Dispatchers.Main) { updateUi(ui.copy(status = "mt+tts all…")) }
            val base = getApplication<Application>().filesDir
            val hi = "मुझे आपके साथ बात करके अच्छा लगा।"
            for (l in Lang.values()) {
                val t0 = System.currentTimeMillis()
                val tr = if (l == Lang.HI) hi else mt?.translate(hi, Lang.HI, l) ?: ""
                val mtMs = System.currentTimeMillis() - t0
                val t1 = System.currentTimeMillis()
                val pcm = tts?.synthesize(tr, l, Voice.M, AppConfig.TTS_NFE_TURBO) ?: FloatArray(0)
                val ttsMs = System.currentTimeMillis() - t1
                try {
                    if (pcm.isNotEmpty()) com.itantra.walkie.ml.WavIO.write16(
                        File(base, "tts_q_${l.name}.wav"), AppConfig.TTS_SR, pcm
                    )
                } catch (_: Exception) {}
                android.util.Log.e(
                    "MTALL", "lg=${l.tag} mtErr=${mt?.lastError?.take(90)} mt=${mtMs}ms mtSame=${tr == hi} tts=${ttsMs}ms " +
                        "ref=${tts?.refSource(l, Voice.M)} frames=${tts?.lastFrames} " +
                        "sec=${"%.2f".format(pcm.size / 24000.0)} chars=${tr.length} " +
                        "mel=[${tts?.lastMelStats}] err=${tts?.lastError} out=$tr"
                )
            }
            withContext(Dispatchers.Main) { updateUi(ui.copy(status = "mt+tts all done")) }
        }
    }
    /**
     * Screens candidate reference prompts dropped into filesDir as `refc_<name>.wav` plus a
     * `refc_<name>.txt` holding its exact transcript, and logs the same verdict the loader
     * computes at startup. This is how a language's own voice gets checked before it ships -
     * a clip that fails the gates silently falls back to a donor, so guessing is invisible.
     */
    private fun refProbe() {
        viewModelScope.launch(Dispatchers.Default) {
            val base = getApplication<Application>().filesDir
            val cands = (base.listFiles { f -> f.name.startsWith("refc_") && f.name.endsWith(".wav") }
                ?: emptyArray()).sortedBy { it.name }
            android.util.Log.e("REFPROBE", "${cands.size} candidates in ${base.absolutePath}")
            for (w in cands) {
                val t = File(base, w.name.removeSuffix(".wav") + ".txt")
                if (!t.exists()) { android.util.Log.e("REFPROBE", "${w.name}: no transcript beside it"); continue }
                val v = try {
                    val wav = com.itantra.walkie.ml.WavIO.read(w)
                    val pcm = if (wav.sr != 24000)
                        com.itantra.walkie.ml.WavIO.resample(wav.samples, wav.sr, 24000) else wav.samples
                    "${"%.2f".format(wav.samples.size / 24000.0)}s src=${wav.sr}Hz " +
                        (tts?.probeRef(pcm, t.readText().trim(), Voice.M) ?: "no tts")
                } catch (e: Exception) { "fail ${e.message}" }
                android.util.Log.e("REFPROBE", "${w.name} $v")
            }
            withContext(Dispatchers.Main) { updateUi(ui.copy(status = "refprobe done")) }
        }
    }

    /**
     * Reads every shipped reference clip as if its speaker had just transmitted: real human
     * speech, eleven languages, both voices. These are the numbers the neutral anchors in
     * [com.itantra.walkie.ml.Prosody] are set against, so this is the page to re-run after
     * anything about the reference pack changes - a tone scale tuned to one language's donor
     * reads as forced in another's.
     */
    private fun prosodyAudit() {
        viewModelScope.launch(Dispatchers.Default) {
            var n = 0
            for (lg in Lang.values()) for (v in Voice.values()) {
                val key = "refs/ref_${lg.name.lowercase()}_${v.name.lowercase()}.wav"
                val pcm = try {
                    val w = com.itantra.walkie.ml.WavIO.readBytes(bundled.bytes(key))
                    com.itantra.walkie.ml.WavIO.resample(w.samples, w.sr, AppConfig.STT_SR)
                } catch (_: Exception) { continue }
                val r = com.itantra.walkie.ml.Prosody.measure(pcm, AppConfig.STT_SR)
                android.util.Log.e(
                    "PROSODY", "$key sec=${"%.2f".format(pcm.size / AppConfig.STT_SR.toDouble())} " +
                        "spread=${"%.3f".format(r.spread)} density=${"%.3f".format(r.density)} " +
                        "dyn=${"%.3f".format(r.dynamics)} voiced=${r.voiced} tone=${(r.animation() * 100).toInt()}%"
                )
                n++
            }
            android.util.Log.e("PROSODY", "done clips=$n")
            withContext(Dispatchers.Main) { updateUi(ui.copy(status = "prosody audit · $n clips")) }
        }
    }

    /**
     * Renders one sentence across the tone scale into filesDir as `tone_<percent>.wav` for the
     * host pitch and duration scripts, and states the guarantee it exists to keep: a neutral
     * reading returns the very array the model solved, not a re-render of it.
     */
    private fun toneRender() {
        viewModelScope.launch(Dispatchers.Default) {
            val base = getApplication<Application>().filesDir
            val text = "मैं ठीक हूँ। आप कैसे हैं?"
            val raw = tts?.synthesize(text, Lang.HI, Voice.M, AppConfig.TTS_NFE_TURBO) ?: FloatArray(0)
            if (raw.isEmpty()) {
                android.util.Log.e("TONE", "no audio · ${tts?.lastError}")
                return@launch
            }
            val P = com.itantra.walkie.ml.Prosody
            val same = P.paint(raw, AppConfig.TTS_SR, com.itantra.walkie.ml.Tone.NEUTRAL)
            android.util.Log.e(
                "TONE", "neutral sameArray=${same === raw} md5=${md5(raw)} / ${md5(same)} " +
                    "rawFrames=${raw.size} peak=${"%.3f".format(peak(raw))}"
            )
            // The frame the other handset has to read back exactly. A neutral tone must put no
            // header on the wire at all, which is what keeps an ordinary transmission the same
            // bytes the transport has always carried.
            val probe = "मैं ठीक हूँ।"
            val plain = com.itantra.walkie.ml.Tone.pack(probe, com.itantra.walkie.ml.Tone.NEUTRAL)
            android.util.Log.e(
                "TONE", "wire plain=${plain.size}B sameAsUtf8=" +
                    (plain.contentEquals(probe.toByteArray(Charsets.UTF_8)))
            )
            for (a in floatArrayOf(-1f, -0.37f, 0.25f, 1f)) {
                val t = com.itantra.walkie.ml.Tone(a)
                val back = com.itantra.walkie.ml.Tone.unpack(com.itantra.walkie.ml.Tone.pack(probe, t))
                android.util.Log.e(
                    "TONE", "wire a=${"%.2f".format(a)} -> ${(t.animation * 100).toInt()}% read back " +
                        "${(back.first.animation * 100).toInt()}% text intact=${back.second == probe}"
                )
            }
            val soft = P.soften(raw, AppConfig.TTS_SR)
            try {
                com.itantra.walkie.ml.WavIO.write16(File(base, "soft_m.wav"), AppConfig.TTS_SR, soft)
            } catch (_: Exception) {}
            android.util.Log.e(
                "TONE", "soft n=${soft.size} len=${"%.3f".format(soft.size.toFloat() / raw.size)} " +
                    "peak=${"%.3f".format(peak(soft))}"
            )
            for (a in floatArrayOf(-1f, -0.5f, 0f, 0.5f, 1f)) {
                val out = P.paint(raw, AppConfig.TTS_SR, com.itantra.walkie.ml.Tone(a))
                val tag = "tone_${(a * 100).toInt()}"
                try {
                    com.itantra.walkie.ml.WavIO.write16(File(base, "$tag.wav"), AppConfig.TTS_SR, out)
                } catch (_: Exception) {}
                android.util.Log.e(
                    "TONE", "$tag a=${"%.2f".format(a)} n=${out.size} " +
                        "len=${"%.3f".format(out.size.toFloat() / raw.size)} " +
                        "sec=${"%.2f".format(out.size / AppConfig.TTS_SR.toDouble())} " +
                        "peak=${"%.3f".format(peak(out))}"
                )
            }
            withContext(Dispatchers.Main) { updateUi(ui.copy(status = "tone renders written")) }
        }
    }

    /**
     * The tone heard out loud on one handset, with nobody having to emote into a microphone for
     * it: one sentence solved once, then played as it came out, animated, and subdued.
     */
    private fun toneAudible() {
        viewModelScope.launch(Dispatchers.Default) {
            val text = "मैं ठीक हूँ। आप कैसे हैं?"
            val raw = tts?.synthesize(text, Lang.HI, Voice.M, nfe()) ?: FloatArray(0)
            if (raw.isEmpty()) {
                withContext(Dispatchers.Main) { updateUi(ui.copy(status = "tone A/B · nothing spoken")) }
                return@launch
            }
            for ((label, a) in listOf("as solved" to 0f, "animated" to 0.75f, "subdued" to -0.75f)) {
                val out = com.itantra.walkie.ml.Prosody.paint(
                    raw, AppConfig.TTS_SR, com.itantra.walkie.ml.Tone(a)
                )
                android.util.Log.e("TONE", "ab $label a=${(a * 100).toInt()}% n=${out.size}")
                withContext(Dispatchers.Main) { updateUi(ui.copy(status = "tone A/B · $label")) }
                player.play(out)
                delay(450)
            }
            withContext(Dispatchers.Main) { updateUi(ui.copy(status = "tone A/B done")) }
        }
    }

    private fun md5(pcm: FloatArray): String {
        val bb = java.nio.ByteBuffer.allocate(pcm.size * 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (v in pcm) bb.putFloat(v)
        return java.security.MessageDigest.getInstance("MD5").digest(bb.array())
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Word error rate for the STT stage, which the SIH problem statement asks teams to
     * report alongside latency. Clips arrive in filesDir as `wer_<id>.wav` with the exact
     * transcript in `wer_<id>.txt`; each is decoded on-device and both strings are logged so
     * WER and CER get computed on the host. Only the hypothesis and the wall-clock are
     * measured here - scoring stays off the device so the harness cannot skew the audio.
     */
    private fun werBenchmark() {
        viewModelScope.launch(Dispatchers.Default) {
            val base = getApplication<Application>().filesDir
            val clips = (base.listFiles { f -> f.name.startsWith("wer_") && f.name.endsWith(".wav") }
                ?: emptyArray()).sortedBy { it.name }
            android.util.Log.e("WER", "start clips=${clips.size} sttReady=${stt?.isReady()} threads=${AppConfig.ORT_THREADS}")
            var done = 0
            for (w in clips) {
                val t = File(base, w.name.removeSuffix(".wav") + ".txt")
                if (!t.exists()) { android.util.Log.e("WER", "${w.name}: no transcript beside it"); continue }
                val ref = t.readText().trim()
                val got = try {
                    val wav = com.itantra.walkie.ml.WavIO.read(w)
                    val pcm = if (wav.sr != AppConfig.STT_SR)
                        com.itantra.walkie.ml.WavIO.resample(wav.samples, wav.sr, AppConfig.STT_SR) else wav.samples
                    val s0 = System.currentTimeMillis()
                    val text = stt?.transcribe(pcm)?.text ?: ""
                    val ms = System.currentTimeMillis() - s0
                    android.util.Log.e("WER", "${w.name} ms=$ms audio=${"%.2f".format(pcm.size / AppConfig.STT_SR.toDouble())}s err=${stt?.lastError}")
                    android.util.Log.e("WER", "${w.name} REF=$ref")
                    android.util.Log.e("WER", "${w.name} HYP=$text")
                    done++
                } catch (e: Exception) {
                    android.util.Log.e("WER", "${w.name} fail ${e.message}")
                }
            }
            android.util.Log.e("WER", "done scored=$done/${clips.size}")
            withContext(Dispatchers.Main) { updateUi(ui.copy(status = "wer done: $done clips")) }
        }
    }

    /**
     * Is the FM solve thread-bound or frame-bound? Logs ms/step over a small
     * threads x frames grid. Answers whether the 3-4s target can be bought with
     * scheduling, or only with fewer steps / fewer frames.
     */
    private fun fmCostProbe() {
        viewModelScope.launch(Dispatchers.Default) {
            withContext(Dispatchers.Main) { updateUi(ui.copy(status = "fm cost probe…")) }
            val n = Runtime.getRuntime().availableProcessors()
            android.util.Log.e("FMCOST", "cores=$n threads=${AppConfig.ORT_THREADS}")
            // Grid sits on the real operating points: 381 frames = one short sentence with
            // the 241-frame Hindi prompt, 521 = a merged two-sentence reply.
            val r = tts?.fmCostProbe(intArrayOf(2, (n / 2).coerceAtLeast(2), 4), intArrayOf(381, 521), 2)
                ?: "no tts"
            android.util.Log.e("FMCOST", r)
            withContext(Dispatchers.Main) { updateUi(ui.copy(status = "fm probe done")) }
        }
    }

    /**
     * Sampling sweep: fixed Hindi sentence over an NFE x guidance grid.
     * Wavs land in filesDir as tts_sweep_n{n}g{g}.wav; pull them and score with
     * scripts/pitch.js + spectral.js, then lock the winner into AppConfig.
     */
    private fun sweepSampling() {
        viewModelScope.launch(Dispatchers.Default) {
            val base = getApplication<Application>().filesDir
            withContext(Dispatchers.Main) { updateUi(ui.copy(status = "sweep running…")) }
            val text = "मैं ठीक हूँ। आप कैसे हैं?"
            for (nfe in listOf(8, 16, 24)) {
                for (g in listOf(1.5f, 2.0f, 2.5f)) {
                    val t0 = System.currentTimeMillis()
                    val pcm = tts?.synthesize(text, Lang.HI, Voice.M, nfe, g) ?: FloatArray(0)
                    val ms = System.currentTimeMillis() - t0
                    val e = tts
                    try {
                        if (pcm.isNotEmpty()) com.itantra.walkie.ml.WavIO.write16(
                            File(base, "tts_sweep_n${nfe}g${g}.wav"), AppConfig.TTS_SR, pcm
                        )
                    } catch (_: Exception) {}
                    android.util.Log.e(
                        "SWEEP",
                        "nfe=$nfe g=$g ms=$ms n=${pcm.size} frames=${e?.lastFrames} " +
                            "enc=${e?.msEnc} fm=${e?.msFm} voclayout=[${e?.lastVocShape}] mel=[${e?.lastMelStats}] err=${e?.lastError}"
                    )
                }
            }
            withContext(Dispatchers.Main) { updateUi(ui.copy(status = "sweep done")) }
        }
    }

    /**
     * Seed x prompt experiment: same Hindi sentence with different init-noise seeds
     * and with a foreign (Marathi) prompt. Decides whether muffled/shrill variance
     * is seed luck (fix: candidate selection) or prompt-bound (fix: prompt repair).
     */
    private fun seedExperiment() {
        viewModelScope.launch(Dispatchers.Default) {
            val base = getApplication<Application>().filesDir
            withContext(Dispatchers.Main) { updateUi(ui.copy(status = "seeds running…")) }
            // Same text, same prompt, only the init noise differs. The point is the SPREAD:
            // if 8 steps scatters the takes across "sounds human" and "sounds robotic" then
            // either more steps or take selection has to buy the consistency. Each take is
            // written out so the listener's ranking can be compared against the engine's own
            // prompt-reconstruction error for it.
            val text = "मैं ठीक हूँ। आप कैसे हैं?"
            val cases = ArrayList<Triple<Int, Long, Pair<Lang, Voice>?>>()
            for (s in listOf(7L, 11L, 123L, 999L, 2024L, 555L)) cases += Triple(8, s, null)
            for (s in listOf(7L, 11L, 123L)) cases += Triple(16, s, null)
            for ((nfe, s, prompt) in cases) {
                val tag = "n${nfe}_s$s"
                val t0 = System.currentTimeMillis()
                val pcm = tts?.synthesize(text, Lang.HI, Voice.M, nfe, AppConfig.TTS_GUIDANCE, s, prompt)
                    ?: FloatArray(0)
                val ms = System.currentTimeMillis() - t0
                val e = tts
                try {
                    if (pcm.isNotEmpty()) com.itantra.walkie.ml.WavIO.write16(
                        File(base, "tts_var_$tag.wav"), AppConfig.TTS_SR, pcm
                    )
                } catch (_: Exception) {}
                android.util.Log.e(
                    "SEEDS",
                    "tag=$tag ms=$ms n=${pcm.size} frames=${e?.lastFrames} " +
                        "promptMel=[${e?.lastPromptStats}] outMel=[${e?.lastMelStats}] err=${e?.lastError}"
                )
            }
            withContext(Dispatchers.Main) { updateUi(ui.copy(status = "seeds done")) }
        }
    }

    /**
     * Validates the shipped FULL recipe end to end: NFE_FULL steps, default
     * guidance, best-of-TSS_CANDIDATES_FULL with the audio scorer. Deterministic
     * (text-hash seeds), so one run proves what every user gets for this text.
     */
    private fun pickValidation() {
        viewModelScope.launch(Dispatchers.Default) {
            val base = getApplication<Application>().filesDir
            withContext(Dispatchers.Main) { updateUi(ui.copy(status = "pick running…")) }
            val cases = listOf(
                "मैं ठीक हूँ। आप कैसे हैं?" to Lang.HI,
                "तुम कसे आहात? मी ठीक आहे." to Lang.MR,
                "তুমি কেমন আছো? আমি ভালো আছি।" to Lang.BN,
            )
            for ((text, lg) in cases) {
                val t0 = System.currentTimeMillis()
                val pcm = tts?.synthesize(
                    text, lg, Voice.M, AppConfig.TTS_NFE_FULL,
                    candidates = AppConfig.TTS_CANDIDATES_FULL,
                ) ?: FloatArray(0)
                val ms = System.currentTimeMillis() - t0
                val e = tts
                try {
                    if (pcm.isNotEmpty()) com.itantra.walkie.ml.WavIO.write16(
                        File(base, "tts_pick_${lg.name}.wav"), AppConfig.TTS_SR, pcm
                    )
                } catch (_: Exception) {}
                android.util.Log.e(
                    "PICK",
                    "lg=${lg.name} ms=$ms n=${pcm.size} scores=${e?.lastCandScores} " +
                        "mel=[${e?.lastMelStats}] err=${e?.lastError}"
                )
            }
            withContext(Dispatchers.Main) { updateUi(ui.copy(status = "pick done")) }
        }
    }

    /**
     * NFE32 single-solve control: same three texts at 32 Euler steps, one seed.
     * Compared against best-of-2 at NFE16 (same FM-eval budget) to decide whether
     * FULL should be "deeper solve" or "two candidates".
     */
    private fun pickValidation32() {
        viewModelScope.launch(Dispatchers.Default) {
            val base = getApplication<Application>().filesDir
            withContext(Dispatchers.Main) { updateUi(ui.copy(status = "pick32 running…")) }
            val cases = listOf(
                "मैं ठीक हूँ। आप कैसे हैं?" to Lang.HI,
                "तुम कसे आहात? मी ठीक आहे." to Lang.MR,
                "তুমি কেমন আছো? আমি ভালো আছি।" to Lang.BN,
            )
            for ((text, lg) in cases) {
                val t0 = System.currentTimeMillis()
                val pcm = tts?.synthesize(text, lg, Voice.M, 32, candidates = 1) ?: FloatArray(0)
                val ms = System.currentTimeMillis() - t0
                val e = tts
                try {
                    if (pcm.isNotEmpty()) com.itantra.walkie.ml.WavIO.write16(
                        File(base, "tts_pick32_${lg.name}.wav"), AppConfig.TTS_SR, pcm
                    )
                } catch (_: Exception) {}
                android.util.Log.e(
                    "PICK32",
                    "lg=${lg.name} ms=$ms n=${pcm.size} mel=[${e?.lastMelStats}] err=${e?.lastError}"
                )
            }
            withContext(Dispatchers.Main) { updateUi(ui.copy(status = "pick32 done")) }
        }
    }

    /**
     * Prompt-vs-text isolation matrix. The same voices that sound muffled with
     * their own prompt may sound fine with a foreign prompt (prompt-bound) or
     * stay muffled (text-bound). Four solves settle it.
     */
    /**
     * Is the non-Hindi choppiness in the prompt or in the solve? Same text, same seed,
     * own-language prompt vs Hindi prompt. If the Hindi-prompted Marathi comes out
     * fluent, the bundled MR/GU/TE refs (not the model) are what poison prosody.
     */
    private fun promptAB() {
        viewModelScope.launch(Dispatchers.Default) {
            withContext(Dispatchers.Main) { updateUi(ui.copy(status = "prompt A/B…")) }
            val base = getApplication<Application>().filesDir
            val mrText = "तुम कसे आहात? मी ठीक आहे."
            val teText = "మీరు ఎలా ఉన్నారు? నేను బాగున్నాను."
            val guText = "તમે કેમ છો? હું ઠીક છું."
            data class AB(val tag: String, val text: String, val lg: Lang, val pl: Lang)
            val cases = listOf(
                AB("mr_own", mrText, Lang.MR, Lang.MR),
                AB("mr_hi", mrText, Lang.MR, Lang.HI),
                AB("te_own", teText, Lang.TE, Lang.TE),
                AB("te_hi", teText, Lang.TE, Lang.HI),
                AB("gu_own", guText, Lang.GU, Lang.GU),
                AB("gu_hi", guText, Lang.GU, Lang.HI),
            )
            for (c in cases) {
                val pcm = tts?.synthesize(c.text, c.lg, Voice.M, AppConfig.TTS_NFE_TURBO,
                    AppConfig.TTS_GUIDANCE, 7L, Pair(c.pl, Voice.M)) ?: FloatArray(0)
                try {
                    if (pcm.isNotEmpty()) com.itantra.walkie.ml.WavIO.write16(
                        File(base, "tts_ab_${c.tag}.wav"), AppConfig.TTS_SR, pcm
                    )
                } catch (_: Exception) {}
                android.util.Log.e("PROMPTAB", "tag=${c.tag} ref=${tts?.refSource(c.lg, Voice.M)} n=${pcm.size} frames=${tts?.lastFrames} " +
                    "cond=${tts?.lastCondFrames} mel=[${tts?.lastMelStats}] err=${tts?.lastError}")
            }
            withContext(Dispatchers.Main) { updateUi(ui.copy(status = "prompt A/B done")) }
        }
    }

    private fun crossMatrix() {
        viewModelScope.launch(Dispatchers.Default) {
            val base = getApplication<Application>().filesDir
            withContext(Dispatchers.Main) { updateUi(ui.copy(status = "xmatrix running…")) }
            val bnText = "তুমি কেমন আছো? আমি ভালো আছি।"
            val mrText = "तुम कसे आहात? मी ठीक आहे."
            data class XC(val tag: String, val text: String, val lg: Lang, val pl: Lang, val seed: Long)
            val cases = listOf(
                XC("bn_txt_mr_prompt", bnText, Lang.BN, Lang.MR, 7L),
                XC("mr_txt_hi_prompt", mrText, Lang.MR, Lang.HI, 7L),
                XC("bn_txt_hi_prompt", bnText, Lang.BN, Lang.HI, 7L),
                XC("mr_txt_bn_prompt", mrText, Lang.MR, Lang.BN, 7L),
            )
            for (c in cases) {
                val t0 = System.currentTimeMillis()
                val pcm = tts?.synthesize(c.text, c.lg, Voice.M, 16, AppConfig.TTS_GUIDANCE, c.seed, Pair(c.pl, Voice.M))
                    ?: FloatArray(0)
                val ms = System.currentTimeMillis() - t0
                val e = tts
                try {
                    if (pcm.isNotEmpty()) com.itantra.walkie.ml.WavIO.write16(
                        File(base, "tts_x_${c.tag}.wav"), AppConfig.TTS_SR, pcm
                    )
                } catch (_: Exception) {}
                android.util.Log.e(
                    "XMATRIX",
                    "tag=${c.tag} ms=$ms n=${pcm.size} frames=${e?.lastFrames} " +
                        "outMel=[${e?.lastMelStats}] err=${e?.lastError}"
                )
            }
            withContext(Dispatchers.Main) { updateUi(ui.copy(status = "xmatrix done")) }
        }
    }

    /** Latency probe: times each pipeline stage and logs to logcat tag BENCH. */
    private fun benchmark() {
        viewModelScope.launch(Dispatchers.Default) {
            // FM cost scales with mel frames, so probe a realistic short walkie-talkie
            // reply as well as a long sentence.
            val cases = listOf(
                "नमस्कार।" to "short",
                "मैं ठीक हूँ, आप कैसे हैं?" to "medium",
                "नमस्कार, मैं ठीक हूँ। आप कैसे हैं? मुझे आज कार्यालय जाना है।" to "long"
            )
            for ((hi, name) in cases) {
                val t0 = System.currentTimeMillis()
                val tr = mt?.translate(hi, Lang.HI, Lang.MR) ?: ""
                val t1 = System.currentTimeMillis()
                val pcm = tts?.synthesize(tr.ifBlank { hi }, Lang.MR, Voice.M, AppConfig.TTS_NFE_TURBO) ?: FloatArray(0)
                val t2 = System.currentTimeMillis()
                val e = tts
                android.util.Log.e("BENCH", "[$name] mt=${t1 - t0}ms tts=${t2 - t1}ms total=${t2 - t0}ms | enc=${e?.msEnc} fm=${e?.msFm} voc=${e?.msVoc} head=${e?.msHead} istft=${e?.msIstft} frames=${e?.lastFrames} condT=${e?.lastCondFrames} | audio=${"%.1f".format(pcm.size / 24000.0)}s")
                android.util.Log.e("BENCH", "[$name] per-step FM ms: ${e?.lastStepMs}")
                android.util.Log.e("BENCH", "[$name] '$hi' -> '$tr'")
            }

            // STT: feed bundled clips whose transcript is known, to check accuracy + speed.
            val base = getApplication<Application>().filesDir
            for (tag in listOf("hi_m", "mr_m")) {
                try {
                    val wav = com.itantra.walkie.ml.WavIO.readBytes(bundled.bytes("refs/ref_$tag.wav"))
                    val speech = com.itantra.walkie.ml.WavIO.resample(wav.samples, wav.sr, AppConfig.STT_SR)
                    val expect = bundled.text("refs/ref_$tag.txt").trim()
                    val s0 = System.currentTimeMillis()
                    val got = stt?.transcribe(speech)?.text ?: ""
                    val s1 = System.currentTimeMillis()
                    android.util.Log.e("BENCH", "STT[$tag] ${s1 - s0}ms audio=${speech.size / AppConfig.STT_SR}s ready=${stt?.isReady()} err=${stt?.lastError}")
                    android.util.Log.e("BENCH", "STT[$tag] expect='$expect'")
                    android.util.Log.e("BENCH", "STT[$tag] got   ='$got'")
                } catch (ex: Exception) {
                    android.util.Log.e("BENCH", "STT[$tag] fail ${ex.message}")
                }
            }

            // Intelligibility oracle: TTS Hindi speech back through the STT model. Real speech
            // round-trips to recognisable text; noise round-trips to nothing.
            try {
                val spoken = "मैं ठीक हूँ। आप कैसे हैं?"
                val audio = tts?.synthesize(spoken, Lang.HI, Voice.M, AppConfig.TTS_NFE_TURBO) ?: FloatArray(0)
                val back = stt?.transcribe(com.itantra.walkie.ml.WavIO.resample(audio, AppConfig.TTS_SR, AppConfig.STT_SR))?.text ?: ""
                android.util.Log.e("BENCH", "ROUNDTRIP tts mel=${tts?.lastMelStats} said='$spoken' heard='$back' samples=${audio.size}")
                android.util.Log.e(
                    "BENCH",
                    "COND tcRows=${tts?.tcRows}/want=${tts?.tcWanted} bins=${tts?.tcBins} tcAbs=${"%.3f".format(tts?.tcAbs ?: 0.0)}" +
                        " vel=${"%.3f".format(tts?.lastVelAbs ?: 0.0)} tc[${tts?.lastTcStats}] sc[${tts?.lastScStats}]"
                )
                android.util.Log.e("BENCH", "TC-PROBE " + tts?.probeTextEnc(spoken, Lang.HI, Voice.M))
                com.itantra.walkie.ml.WavIO.write16(File(getApplication<Application>().cacheDir, "roundtrip_tts.wav"), AppConfig.TTS_SR, audio)
            } catch (ex: Exception) {
                android.util.Log.e("BENCH", "ROUNDTRIP fail ${ex.message}")
            }

            // Control: human prompt mel straight through the vocoder. If this round-trips,
            // the text-encoder/FM is at fault, not the head/iSTFT.
            try {
                val wav = com.itantra.walkie.ml.WavIO.readBytes(bundled.bytes("refs/ref_hi_m.wav"))
                val pcm = com.itantra.walkie.ml.WavIO.resample(wav.samples, wav.sr, AppConfig.TTS_SR)
                val mel = tts?.refMelOf(pcm) ?: emptyArray()
                val rebuilt = tts?.renderMel(mel) ?: FloatArray(0)
                val expect = bundled.text("refs/ref_hi_m.txt").trim()
                val heard = stt?.transcribe(com.itantra.walkie.ml.WavIO.resample(rebuilt, AppConfig.TTS_SR, AppConfig.STT_SR))?.text ?: ""
                android.util.Log.e("BENCH", "VOCODE-CONTROL mel=${mel.size}f stats=${tts?.stats(mel)} expect='$expect' heard='$heard'")
                android.util.Log.e("BENCH", "FRONTEND " + tts?.frontendDiag(pcm))
                com.itantra.walkie.ml.WavIO.write16(File(getApplication<Application>().cacheDir, "rebuilt_ref.wav"), AppConfig.TTS_SR, rebuilt)
            } catch (ex: Exception) {
                android.util.Log.e("BENCH", "VOCODE-CONTROL fail ${ex.message}")
            }
        }
    }

    /** Per-stage signal levels, to tell an empty mel from a muted vocoder. */
    private fun diagnose() {
        viewModelScope.launch(Dispatchers.Default) {
            val base = getApplication<Application>().filesDir
            android.util.Log.e("DIAG", "refs ${tts?.refInfo()}")
            val cases = listOf(
                "तू कोण आहेस?" to Lang.MR,
                "आप कौन है" to Lang.HI,
                "मैं ठीक हूँ। आप कैसे हैं?" to Lang.HI,
                "नमस्कार।" to Lang.HI,
                "तुम कसे आहात? मी ठीक आहे." to Lang.MR,
                // Cross the text against the other language's reference: if the pairing
                // rather than the script decides audibility, the prompt pack is at fault.
                "तू कोण आहेस?" to Lang.HI,
                "आप कौन है" to Lang.MR
            )
            cases.forEachIndexed { i, (txt, lg) ->
                val pcm = tts?.synthesize(txt, lg, ui.voice, nfe()) ?: FloatArray(0)
                val e = tts
                var pk = 0f; var sq = 0.0
                for (v in pcm) { val a = if (v < 0) -v else v; if (a > pk) pk = a; sq += v.toDouble() * v }
                val rms = if (pcm.isEmpty()) 0.0 else Math.sqrt(sq / pcm.size)
                android.util.Log.e("DIAG", "#$i '$txt' lg=$lg n=${pcm.size} frames=${e?.lastFrames} cond=${e?.lastCondFrames}" +
                    " tcAbs=${"%.3f".format(e?.tcAbs ?: 0.0)} vel=${"%.3f".format(e?.lastVelAbs ?: 0.0)}" +
                    " peak=${"%.3f".format(pk)} rms=${"%.4f".format(rms)} err=${e?.lastError}")
                android.util.Log.e("DIAG", "#$i mel=${e?.lastMelStats}")
                // Level alone can't tell quiet speech from a click over silence, so ask the
                // recogniser whether anything intelligible came out.
                val back = try {
                    stt?.transcribe(com.itantra.walkie.ml.WavIO.resample(pcm, 24000, AppConfig.STT_SR))?.text ?: ""
                } catch (ex: Exception) { "stt fail ${ex.message}" }
                android.util.Log.e("DIAG", "#$i said='$txt' heard='$back'")
                try { com.itantra.walkie.ml.WavIO.write16(File(base, "diag_$i.wav"), 24000, pcm) } catch (_: Exception) {}
            }
        }
    }

    /** Text-encoder only: cheap way to read the duration law across utterance lengths. */
    private fun probe() {
        viewModelScope.launch(Dispatchers.Default) {
            val base = getApplication<Application>().filesDir
            try {
                val wav = com.itantra.walkie.ml.WavIO.readBytes(bundled.bytes("refs/ref_hi_m.wav"))
                android.util.Log.e("BENCH", "FRONTEND " + tts?.frontendDiag(wav.samples))
            } catch (ex: Exception) { android.util.Log.e("BENCH", "FRONTEND fail ${ex.message}") }
            for (t in listOf("नमस्कार।", "आप कैसे हैं?", "मैं ठीक हूँ। आप कैसे हैं?", "मुझे आज कार्यालय जाना है।")) {
                android.util.Log.e("BENCH", "TC " + (tts?.probeTextEnc(t, Lang.HI, Voice.M) ?: "nil"))
            }
        }
    }

    /** Stage timings in the readout's own voice: seconds, one decimal, no engineering units. */
    private fun sec(ms: Long): String = String.format("%.1fs", ms / 1000.0)

    /**
     * What a reading is called to the operator. The words are the ones a crew already uses about a
     * transmission; the number behind them is one measured axis, not a claim about a feeling.
     */
    fun toneWord(a: Float): String = when {
        a >= 0.55f -> "urgent"
        a >= 0.20f -> "animated"
        a > -0.20f -> if (a == 0f) "" else "even"
        a > -0.55f -> "flat"
        else -> "subdued"
    }

    /** The tone as the bubble carries it: nothing at all when there was nothing to read. */
    private fun toneMark(tone: com.itantra.walkie.ml.Tone): String {
        val w = if (tone.neutral) "" else toneWord(tone.animation)
        return if (w.isEmpty()) "" else " · said $w"
    }

    /** Stage split of the last synthesize(); the log-only half of the latency story. */
    private fun ttsStages(): String = tts?.let {
        "enc=${it.msEnc} fm=${it.msFm} voc=${it.msVoc} head=${it.msHead} istft=${it.msIstft} " +
            "frames=${it.lastFrames}/cond=${it.lastCondFrames} steps=[${it.lastStepMs.joinToString(",")}]"
    } ?: ""

    /** One spoken reply: audio for replay, ms of solving, ms spent rendering, error. */
    private data class Spoken(val pcm: FloatArray, val synthMs: Long, val playedMs: Long, val err: String)

    /**
     * Renders the whole reply, then plays it as one continuous buffer.
     *
     * Speaking each sentence as it rendered was built for latency and removed for fluency:
     * a chunk's FM solve costs ~9x the audio it produces, so while sentence 1 played its
     * 1.6 s, sentence 2 was still being solved for ~10 s - the walkie spoke, went silent,
     * and spoke again. Keeping one AudioTrack open across the parts did not fix it either
     * (measured 16.6 s of dead air mid-reply), because the bytes genuinely do not exist yet.
     * Buffering costs first-sound latency it cannot buy back, and gives back the connected
     * per-sentence phrasing the whole feature had destroyed.
     */
    private fun speakReply(text: String, lg: Lang, v: Voice, tone: com.itantra.walkie.ml.Tone = com.itantra.walkie.ml.Tone.NEUTRAL): Spoken {
        val t0 = System.currentTimeMillis()
        val solved = tts?.synthesize(text, lg, v, nfe(), candidates = candidates()) ?: FloatArray(0)
        // Two trims, in order, both skippable. The male one is standing: the solve comes out of
        // the vocoder brighter and a few Hz higher than the voice prompt that taught it, which is
        // what a listener calls thin and robotic. The tone one only moves what the caller's own
        // voice moved. With both off, what reaches the speaker is exactly what the model solved.
        val trimmed = if (solved.isEmpty() || v != Voice.M || !ui.softMale) solved
        else com.itantra.walkie.ml.Prosody.soften(solved, AppConfig.TTS_SR)
        val out = if (trimmed.isEmpty() || tone.neutral || !ui.matchTone) trimmed
        else com.itantra.walkie.ml.Prosody.paint(trimmed, AppConfig.TTS_SR, tone)
        val synth = System.currentTimeMillis() - t0
        if (out.isNotEmpty()) player.play(out)
        val err = listOf(tts?.lastError ?: "", player.lastError).firstOrNull { it.isNotBlank() } ?: ""
        return Spoken(out, synth, player.lastPlayedMs, err)
    }

    /** Replay button on a chat bubble: re-plays that line's cached TTS audio. */
    fun replay(id: Long) {
        viewModelScope.launch(Dispatchers.Default) {
            val pcm = cachedAudio(id) ?: return@launch
            withContext(Dispatchers.Main) {
                setSpeaking(id, true)
                updateUi(ui.copy(status = "replaying…"))
            }
            player.play(pcm)
            withContext(Dispatchers.Main) {
                setSpeaking(id, false)
                updateUi(ui.copy(status = if (player.lastError.isBlank()) "replayed" else player.lastError))
            }
        }
    }

    private fun nfe() = if (ui.turbo) AppConfig.TTS_NFE_TURBO else AppConfig.TTS_NFE_FULL

    /** FULL mode spends latency on a 2nd candidate and keeps the better solve. */
    private fun candidates() = if (ui.turbo) 1 else AppConfig.TTS_CANDIDATES_FULL

    /**
     * MT for one turn, plus the note to show under the bubble. The bundled IndicTrans2
     * export is Indic-to-Indic only - an English target comes back as Hindi - so those
     * pairs are passed through untranslated and labelled, rather than pretending.
     * Returns the text and the far panel's subtitle.
     */
    private fun translateTurn(text: String, s: Lang, t: Lang): Pair<String, String> {
        if (!ui.translateOn || mt == null || s == t) return text to t.native
        if (s == Lang.EN || t == Lang.EN) return text to "${t.native} · English MT needs a bigger model"
        return (mt!!.translate(text, s, t).ifBlank { text }) to t.native
    }

    /**
     * Speaks one line in this operator's own language. Not a showpiece: it is how a field team
     * proves the speaker, the voice model and the language choice all work on this handset before
     * trusting them to carry a real transmission.
     */
    fun testVoice() {
        viewModelScope.launch(Dispatchers.Default) {
            withContext(Dispatchers.Main) { updateUi(ui.copy(status = "testing voice…")) }
            val sp = speakReply(ttsTestSentence(ui.tgt), ui.tgt, ui.voice)
            withContext(Dispatchers.Main) {
                updateUi(ui.copy(
                    status = if (sp.pcm.isNotEmpty())
                        "voice ok · ${sec(sp.synthMs)} to speak"
                    else "nothing came out · ${sp.err.ifBlank { "no audio" }}"
                ))
            }
        }
    }

    fun setVoice(v: Voice) { updateUi(ui.copy(voice = v)) }
    fun setTranslateEnabled(on: Boolean) { updateUi(ui.copy(translateOn = on)) }
    fun setTurbo(on: Boolean) { updateUi(ui.copy(turbo = on)) }
    fun setMatchTone(on: Boolean) { updateUi(ui.copy(matchTone = on)) }
    fun setSoftMale(on: Boolean) { updateUi(ui.copy(softMale = on)) }
    fun setStatus(s: String) { updateUi(ui.copy(status = s)) }

    override fun onCleared() { stt?.close(); mt?.close(); tts?.close(); env.close() }
}
