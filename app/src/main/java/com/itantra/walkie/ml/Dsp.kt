package com.itantra.walkie.ml

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Radix-2 FFT/IFFT + STFT/iSTFT helpers (Double precision). */
object Fft {
    fun fft(re: DoubleArray, im: DoubleArray, invert: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = 2 * PI / len * (if (invert) 1 else -1)
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cwr = 1.0; var cwi = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cwr - im[i + k + len / 2] * cwi
                    val vi = re[i + k + len / 2] * cwi + im[i + k + len / 2] * cwr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val nwr = cwr * wr - cwi * wi; cwi = cwr * wi + cwi * wr; cwr = nwr
                }
                i += len
            }
            len = len shl 1
        }
        if (invert) for (i in 0 until n) { re[i] = re[i] / n; im[i] = im[i] / n }
    }

    /** Analysis: reflect-padded (center=true), windowed frames -> power spectra [frames][nfft/2+1]. */
    fun stftPower(pcm: FloatArray, win: FloatArray, hop: Int, nfft: Int): Array<DoubleArray> {
        val pad = nfft / 2
        val ext = FloatArray(pcm.size + 2 * pad)
        for (i in pcm.indices) ext[i + pad] = pcm[i]
        for (i in 0 until pad) { ext[pad - 1 - i] = pcm.getOrElse(1 + i) { 0f }; ext[pad + pcm.size + i] = pcm.getOrElse(pcm.size - 2 - i) { 0f } }
        val frames = 1 + pcm.size / hop
        val bins = nfft / 2 + 1
        val wlen = win.size
        return Array(frames) { f ->
            val re = DoubleArray(nfft); val im = DoubleArray(nfft)
            for (i in 0 until wlen) {
                val s = ext.getOrElse(f * hop + i) { 0f }.toDouble() * win[i]
                re[(nfft - wlen) / 2 + i] = s
            }
            fft(re, im, false)
            DoubleArray(bins) { k -> re[k] * re[k] + im[k] * im[k] }
        }
    }

    /** Synthesis: complex specs [frames][bins] -> overlap-add waveform. */
    fun istft(re: Array<DoubleArray>, im: Array<DoubleArray>, win: FloatArray, hop: Int, nfft: Int): FloatArray {
        val frames = re.size
        val outLen = (frames - 1) * hop + nfft
        val out = DoubleArray(outLen); val wsum = DoubleArray(outLen)
        val wlen = win.size; val off = (nfft - wlen) / 2
        val fre = DoubleArray(nfft); val fim = DoubleArray(nfft)
        for (f in 0 until frames) {
            for (k in 0 until nfft) {
                if (k < re[f].size) { fre[k] = re[f][k]; fim[k] = im[f][k] }
                else { fre[k] = fre[nfft - k]; fim[k] = -fim[nfft - k] }
            }
            fft(fre, fim, true)
            for (i in 0 until wlen) {
                val o = f * hop + off + i
                if (o in 0 until outLen) { out[o] = out[o] + fre[i] * win[i]; wsum[o] = wsum[o] + win[i] * win[i] }
            }
        }
        return FloatArray(outLen) { i -> if (wsum[i] > 1e-8) (out[i] / wsum[i]).toFloat() else 0f }
    }
}

/** Minimal WAV reader/writer (PCM16 + IEEE float32, any chunk layout). */
object WavIO {
    data class Wav(val sr: Int, val ch: Int, val samples: FloatArray)

    fun read(f: File): Wav = readBytes(f.readBytes())

    fun readBytes(b: ByteArray): Wav {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        require(b.sliceArray(0..3).toString(Charsets.US_ASCII) == "RIFF")
        val fmt = bb.getShort(20).toInt() and 0xFFFF
        val ch = bb.getShort(22).toInt() and 0xFFFF
        val sr = bb.getInt(24)
        val bits = bb.getShort(34).toInt() and 0xFFFF
        var o = 12
        var dataOff = -1; var dataLen = 0
        while (o < b.size - 8) {
            val id = b.sliceArray(o until o + 4).toString(Charsets.US_ASCII)
            val sz = bb.getInt(o + 4)
            if (id == "data") { dataOff = o + 8; dataLen = sz; break }
            o += 8 + sz + (sz and 1)
        }
        require(dataOff >= 0)
        // Absolute reads are done straight off the array: Android's HeapByteBuffer indexes
        // absolute getters from the backing array start, so a wrap(b, dataOff, len) offset
        // silently shifts every sample back into the RIFF header.
        val stride = bits / 8 * ch
        val avail = minOf(dataLen, b.size - dataOff)
        val n = avail / stride
        val out = FloatArray(n) { i ->
            val p = dataOff + i * stride
            if (fmt == 3 && bits == 32) {
                java.lang.Float.intBitsToFloat(
                    (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or
                        ((b[p + 2].toInt() and 0xFF) shl 16) or ((b[p + 3].toInt() and 0xFF) shl 24)
                )
            } else when (bits / 8) {
                2 -> le(b, p, 2).toShort() / 32768f
                4 -> le(b, p, 4).toInt() / 2147483648f
                1 -> ((b[p].toInt() and 0xFF) - 128) / 128f
                else -> 0f
            }
        }
        return Wav(sr, ch, out)
    }

    private fun le(b: ByteArray, p: Int, width: Int): Long {
        var v = 0L
        for (i in 0 until width) v = v or ((b[p + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    fun write16(path: File, sr: Int, pcm: FloatArray) {
        val n = pcm.size
        val bb = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + n * 2); bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()); bb.putInt(16); bb.putShort(1); bb.putShort(1)
        bb.putInt(sr); bb.putInt(sr * 2); bb.putShort(2); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(n * 2)
        for (s in pcm) bb.putShort((s.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        path.writeBytes(bb.array())
    }

    /** Naive linear resample. */
    fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to) return x
        val n = (x.size.toLong() * to / from).toInt().coerceAtLeast(1)
        return FloatArray(n) { i ->
            val p = i.toDouble() * from / to
            val i0 = p.toInt().coerceIn(0, x.size - 1)
            val i1 = (i0 + 1).coerceIn(0, x.size - 1)
            (x[i0] * (1 - (p - i0)) + x[i1] * (p - i0)).toFloat()
        }
    }
}
