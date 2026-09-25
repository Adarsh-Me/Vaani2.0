package com.itantra.walkie.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 24kHz mono float PCM playback. Saves last output for verification.
 *
 * One utterance = one buffer = one AudioTrack, and the call blocks until it has rendered.
 * A sentence-at-a-time stream was built and removed here: the FM solve costs ~9x the audio
 * it produces, so part 2 did not exist while part 1 finished playing and the listener heard
 * 16.6 s of silence in the middle of a two-sentence reply. Buffering first costs latency it
 * cannot win back, and buys the fluency the whole feature exists for.
 *
 * Reliability design (the old streaming loop reported "playback cut short (0/N)" whenever
 * the emulator's audio sink stalled, even with valid audio):
 * - single-flight: concurrent play() calls serialize instead of opening two
 *   AudioTracks that fight over the sink.
 * - short clips (the normal TTS case) prefer MODE_STATIC: one blocking write, then
 *   play. No per-chunk write loop to starve. Static is a preference, not a
 *   requirement - HALs that refuse the whole-clip buffer fall back to streaming.
 * - completion waits on a marker listener first, polling second; an error is
 *   only reported when bytes could not even be queued.
 * - the wav dump happens BEFORE playback, so a playback stall never loses evidence.
 */
class Player(private val cacheDir: File) {
    var lastPlayedSamples: Int = 0
        private set
    var lastError: String = ""
        private set
    /** Frames the hardware had actually rendered when we released. */
    var lastHeadPosition: Int = 0
        private set
    var lastWasStatic: Boolean = false
        private set
    /** Wall clock the last play() spent rendering, ms. */
    var lastPlayedMs: Long = 0
        private set
    private val lock = Any()

    fun play(pcm24k: FloatArray) {
        lastError = ""
        lastHeadPosition = 0
        lastWasStatic = false
        lastPlayedMs = 0
        if (pcm24k.isEmpty()) { lastError = "nothing to play"; return }
        lastPlayedSamples = pcm24k.size
        // Dump first: playback must never be able to destroy the evidence.
        try {
            com.itantra.walkie.ml.WavIO.write16(File(cacheDir, "last_tts.wav"), 24000, pcm24k)
        } catch (_: Exception) {}
        val t0 = System.currentTimeMillis()
        synchronized(lock) { playLocked(pcm24k) }
        lastPlayedMs = System.currentTimeMillis() - t0
    }

    /** @return an initialised track, or null when this (mode, buffer) pair is refused. */
    private fun buildTrack(bufBytes: Int, mode: Int): AudioTrack? {
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                )
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
                )
                .setBufferSizeInBytes(bufBytes)
                .setTransferMode(mode).build()
        } catch (e: Exception) {
            android.util.Log.e("PLAYER", "build mode=$mode bytes=$bufBytes threw ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            android.util.Log.e("PLAYER", "build mode=$mode bytes=$bufBytes refused state=${t.state}")
            try { t.release() } catch (_: Exception) {}
            return null
        }
        return t
    }

    private fun playLocked(pcm24k: FloatArray) {
        // Convert once, outside the track lifetime.
        val s16 = ShortArray(pcm24k.size) { i -> (pcm24k[i].coerceIn(-1f, 1f) * 32767).toInt().toShort() }
        val minBuf = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            lastError = "no audio sink (minBuf=$minBuf)"
            android.util.Log.e("PLAYER", lastError)
            return
        }
        // MODE_STATIC needs a buffer as large as the whole clip, and some HALs
        // answer that with STATE_UNINITIALIZED - which mutes every message. Try
        // static for short clips, degrade to streaming rather than lose audio.
        val streamBuf = minBuf.coerceAtLeast(24000 * 2)
        var useStatic = s16.size <= 24000 * 12
        var t: AudioTrack? = if (useStatic) buildTrack(s16.size * 2, AudioTrack.MODE_STATIC) else null
        if (t == null) {
            useStatic = false
            t = buildTrack(streamBuf, AudioTrack.MODE_STREAM)
        }
        val track = t
        if (track == null) {
            lastError = "audiotrack init fail (static and stream both refused)"
            android.util.Log.e("PLAYER", lastError)
            return
        }
        lastWasStatic = useStatic
        try {
            if (useStatic) playStatic(track, s16) else playStreamed(track, s16)
        } catch (e: Exception) {
            lastError = "play: ${e.message}"
            android.util.Log.e("PLAYER", lastError)
        } finally {
            try { track.release() } catch (_: Exception) {}
        }
    }

    private fun playStatic(t: AudioTrack, s16: ShortArray) {
        val w = t.write(s16, 0, s16.size)
        if (w < s16.size) {
            lastError = if (w < 0) "audiotrack write err=$w" else "audiotrack short write $w/${s16.size}"
            android.util.Log.e("PLAYER", lastError)
            return
        }
        // Completion signal: marker at the last frame.
        val done = CountDownLatch(1)
        try {
            t.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(track: AudioTrack?) { done.countDown() }
                override fun onPeriodicNotification(track: AudioTrack?) {}
            })
            t.notificationMarkerPosition = s16.size
        } catch (_: Exception) {}
        t.play()
        // write() queued everything OK, so from here on a stall is a sink
        // problem, not a playback bug: wait, then report what happened
        // instead of crying "cut short".
        val waitMs = s16.size * 1000L / 24000L + 4_000L
        val signalled = try { done.await(waitMs, TimeUnit.MILLISECONDS) } catch (_: Exception) { false }
        // Drain check: give the head a moment, then read it once.
        val deadline = System.currentTimeMillis() + 1500L
        var head = 0
        while (System.currentTimeMillis() < deadline) {
            head = try { t.playbackHeadPosition } catch (_: Exception) { head }
            if (head >= s16.size || signalled) break
            try { Thread.sleep(25) } catch (_: InterruptedException) { Thread.interrupted() }
        }
        head = try { t.playbackHeadPosition } catch (_: Exception) { head }
        lastHeadPosition = head
        try { t.stop() } catch (_: Exception) {}
        android.util.Log.e("PLAYER", "static n=${s16.size} marker=$signalled head=$head playState=${t.playState}")
        if (head <= 0 && !signalled) {
            // Bytes are queued and valid (last_tts.wav proves it); the sink
            // never moved. Audible on most devices anyway once the sink
            // un-stalls, so warn — don't fail the message.
            lastError = "sink stall (head 0/${s16.size}, audio saved)"
        }
    }

    private fun playStreamed(t: AudioTrack, s16: ShortArray) {
        t.play()
        var o = 0
        var frames = 0
        while (o < s16.size) {
            val n = minOf(4096, s16.size - o)
            val w = t.write(s16, o, n)
            if (w < 0) { lastError = "audiotrack write err=$w"; break }
            frames += w
            o += w
        }
        val deadline = System.currentTimeMillis() + frames * 1000L / 24000L + 4_000L
        while (t.playbackHeadPosition < frames && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(25) } catch (_: InterruptedException) { Thread.interrupted() }
        }
        lastHeadPosition = try { t.playbackHeadPosition } catch (_: Exception) { frames }
        try { t.stop() } catch (_: Exception) {}
        android.util.Log.e("PLAYER", "stream n=$frames head=$lastHeadPosition")
        if (lastHeadPosition < frames * 0.98 && lastError.isBlank()) {
            lastError = "tail clipped ($lastHeadPosition/$frames)"
        }
    }
}
