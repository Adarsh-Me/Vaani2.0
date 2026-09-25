package com.itantra.walkie.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean

/** 16kHz mono capture. start() begins buffering, stop() returns FloatArray PCM. */
class MicRecorder {
    private var rec: AudioRecord? = null
    private var thread: Thread? = null
    private val chunks = ArrayList<ShortArray>()
    private val running = AtomicBoolean(false)
    var lastError: String = ""

    fun start() {
        try {
            stopSilently()
            val sr = 16000
            val bs = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                .coerceAtLeast(4096)
            val r = AudioRecord(MediaRecorder.AudioSource.MIC, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bs * 2)
            if (r.state != AudioRecord.STATE_INITIALIZED) { lastError = "mic init fail"; return }
            rec = r
            chunks.clear()
            running.set(true)
            r.startRecording()
            thread = Thread {
                val buf = ShortArray(bs / 2)
                while (running.get()) {
                    try {
                        val n = r.read(buf, 0, buf.size)
                        if (n > 0) synchronized(chunks) { chunks.add(buf.copyOf(n)) }
                    } catch (_: Exception) { break }
                }
            }.also { it.start() }
        } catch (e: Exception) { lastError = "mic: ${e.message}" }
    }

    fun stop(): FloatArray {
        running.set(false)
        try { thread?.join(500) } catch (_: Exception) {}
        thread = null
        val total = synchronized(chunks) { chunks.sumOf { it.size } }
        val out = FloatArray(total)
        var o = 0
        synchronized(chunks) { for (c in chunks) { for (s in c) out[o++] = s / 32768f } }
        stopSilently()
        return out
    }

    private fun stopSilently() {
        try { rec?.stop() } catch (_: Exception) {}
        try { rec?.release() } catch (_: Exception) {}
        rec = null
    }
}
