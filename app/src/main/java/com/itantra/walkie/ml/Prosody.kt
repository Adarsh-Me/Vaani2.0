package com.itantra.walkie.ml

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How a transmission was said, as distinct from what was said.
 *
 * One measured axis rather than a mood wheel. Pitch movement, speech density and emphasis
 * separate an animated delivery from a flat one reliably; valence - whether a person is happy or
 * angry - does not, and a radio that guesses wrong about what someone feels is worse than one
 * that says nothing. So this reads *animation* and the reply is spoken back in the same shade of
 * it, which is also what a good operator does on a real handset.
 */
data class Tone(val animation: Float = 0f) {
    /** Exactly zero means untouched: no resample, no gain, no tilt. */
    val neutral: Boolean get() = animation == 0f

    /** Wire form: a signed percent, so a whole tone fits in one byte of a Bluetooth frame. */
    val wire: Byte get() = (animation * 100f).toInt().coerceIn(-WIRE_MAX, WIRE_MAX).toByte()

    companion object {
        val NEUTRAL = Tone(0f)
        const val WIRE_MAX = 100
        fun fromWire(v: Int): Tone = Tone(v.coerceIn(-WIRE_MAX, WIRE_MAX) / 100f)

        /**
         * Framing for the tone in front of a message body. A neutral tone writes nothing, so the
         * common case stays exactly the bytes the transport has always carried; a tone is
         * announced by 0x01, which is not a UTF-8 lead byte and cannot arrive from a keyboard or
         * a recogniser, followed by one signed percent.
         */
        private const val MARK = 0x01

        fun pack(text: String, tone: Tone): ByteArray =
            wrap(text.toByteArray(Charsets.UTF_8), tone)

        /**
         * [pack] for a body that is already framed - a compressed message, for instance - so the
         * tone can ride in front of bytes the sender produced rather than in front of raw text.
         */
        fun wrap(body: ByteArray, tone: Tone): ByteArray {
            if (tone.neutral) return body
            return ByteArray(2 + body.size).also {
                it[0] = MARK.toByte(); it[1] = tone.wire
                body.copyInto(it, 2)
            }
        }

        fun unpack(all: ByteArray): Pair<Tone, String> =
            split(all).let { it.first to String(it.second, Charsets.UTF_8) }

        /** Inverse of [wrap]: the tone, and the bytes that followed it untouched. */
        fun split(all: ByteArray): Pair<Tone, ByteArray> {
            if (all.size >= 2 && all[0] == MARK.toByte()) {
                return fromWire(all[1].toInt()) to all.copyOfRange(2, all.size)
            }
            return NEUTRAL to all
        }
    }
}

/**
 * Reads a [Tone] off captured speech, and paints one onto synthesized speech.
 *
 * The read side works on the sender's own microphone bytes, before recognition, so a tone exists
 * even for a transmission whose words the recogniser mangles. The paint side is deliberately
 * post-processing rather than model conditioning: IndicF5 takes its prosody from a reference
 * clip, the pack ships one trusted clip per language and voice, and there is no emotional second
 * clip to select. What is available is the solved waveform, and a ±7 % move in pace, pitch and
 * brightness is enough for a listener to hear that the reply matched how they spoke - while a
 * neutral reading hands back the exact bytes the model produced, untouched.
 */
object Prosody {

    /**
     * The three measurements, and the anchors they are compared against. Every anchor is the
     * middle of what real reference speech scored on-device, so a value near zero means "like a
     * normal reading", not "like a fixed guess".
     */
    data class Reading(
        /** Pitch movement: (p85 - p15) / median F0 over voiced frames. */
        val spread: Float,
        /** Voiced fraction of the clip: urgent speech is dense, hesitant speech is not. */
        val density: Float,
        /** Loud-frame energy over median frame energy: how much emphasis the speaker used. */
        val dynamics: Float,
        /** Voiced frames actually measured. Below [MIN_VOICED] no tone is claimed at all. */
        val voiced: Int,
    ) {
        val usable: Boolean get() = voiced >= MIN_VOICED

        fun animation(): Float {
            if (!usable) return 0f
            val s = (spread - SPREAD_MID) / SPREAD_SPAN
            val d = (density - DENSITY_MID) / DENSITY_SPAN
            val y = (dynamics - DYNAMICS_MID) / DYNAMICS_SPAN
            // Movement carries the judgement; pace and emphasis only corroborate it.
            return (0.55f * s + 0.30f * d + 0.15f * y).coerceIn(-1f, 1f) * GAIN
        }
    }

    /** Measures the sender's own voice. [pcm] is any sample rate; it is only ever relative. */
    fun measure(pcm: FloatArray, sr: Int): Reading {
        if (pcm.size < sr / 4) return Reading(0f, 0f, 0f, 0)
        val win = (sr * 0.04f).toInt().coerceAtLeast(64)
        val hop = (sr * 0.02f).toInt().coerceAtLeast(16)
        val lo = (sr / 380f).toInt()      // 380 Hz
        val hi = (sr / 75f).toInt()       // 75 Hz
        val energy = ArrayList<Float>()
        val f0 = ArrayList<Float>()
        var frames = 0
        var start = 0
        while (start + win <= pcm.size) {
            frames++
            var sq = 0.0
            for (i in 0 until win) sq += (pcm[start + i] * pcm[start + i]).toDouble()
            val e = sqrt(sq / win).toFloat()
            energy += e
            f0 += if (e > 0.008f) f0At(pcm, start, win, lo, hi, sr) else 0f
            start += hop
        }
        if (frames < 4) return Reading(0f, 0f, 0f, 0)
        val voiced = f0.count { it > 0f }
        if (voiced < MIN_VOICED) return Reading(0f, densityOf(voiced, frames), 0f, voiced)
        val sorted = f0.filter { it > 0f }.sorted()
        val med = sorted[sorted.size / 2]
        val spread = (sorted[(sorted.size * 0.85f).toInt().coerceAtMost(sorted.size - 1)] -
            sorted[(sorted.size * 0.15f).toInt()]) / max(med, 1f)
        val es = energy.sorted()
        val eMed = max(es[es.size / 2], 1e-4f)
        val eHigh = es[(es.size * 0.90f).toInt().coerceAtMost(es.size - 1)]
        return Reading(spread, densityOf(voiced, frames), eHigh / eMed, voiced)
    }

    private fun densityOf(voiced: Int, frames: Int): Float =
        if (frames <= 0) 0f else voiced.toFloat() / frames

    /** One frame's pitch by autocorrelation, or 0 when the frame is not periodic enough. */
    private fun f0At(pcm: FloatArray, at: Int, win: Int, lo: Int, hi: Int, sr: Int): Float {
        var mean = 0.0
        for (i in 0 until win) mean += pcm[at + i]
        mean /= win
        var e = 0.0
        for (i in 0 until win) { val d = pcm[at + i] - mean; e += d * d }
        if (e < 1e-6) return 0f
        val limit = min(hi, win / 2)
        var best = -1f
        var bestLag = 0
        var lag = lo
        while (lag <= limit) {
            var c = 0.0
            var n = 0.0
            var m = 0.0
            for (i in 0 until win - lag) {
                val a = pcm[at + i] - mean
                val b = pcm[at + i + lag] - mean
                c += a * b; n += a * a; m += b * b
            }
            val r = if (n * m <= 0) 0f else (c / sqrt(n * m)).toFloat()
            if (r > best) { best = r; bestLag = lag }
            lag++
        }
        return if (bestLag > 0 && best > 0.34f) sr / bestLag.toFloat() else 0f
    }

    /**
     * Renders [pcm] in [t]. A neutral tone returns the array it was handed - not a copy, not a
     * resample of the same numbers - which is what lets the feature ship without touching the
     * voice quality every language was tuned to.
     */
    fun paint(pcm: FloatArray, sr: Int, t: Tone): FloatArray {
        if (t.neutral || pcm.isEmpty()) return pcm
        val a = t.animation
        // Pace and pitch move together, as they do in a real voice: reading the waveform through
        // a cubic interpolator at a slightly different rate is the whole trick, and it cannot
        // smear a consonant the way a phase vocoder does when it is asked for 7 %.
        val out = stretch(pcm, 1f + PITCH_SPAN * a)
        shelf(out, sr, TILT_HZ, TILT_DB * a)
        level(out, 1f + LEVEL_SPAN * a)
        return out
    }

    /**
     * The male cut's standing trim, applied before any tone.
     *
     * The complaint was "his pitch is high and it sounds robotic". Measured, the pitch was only
     * part of it: the Hindi male prompt sits at a 103 Hz median with 0.7 % of its energy above
     * 3 kHz, and the Marathi reply it conditions came back at 110 Hz with 1.9 % - the vocoder
     * adds roughly twice the buzz band and lifts the register above the voice it is cloning,
     * which is what a listener hears as thin and robotic. So this walks the answer back toward
     * its own prompt rather than toward an idea of what a man should sound like. After the trim
     * the same reply measures 103 Hz and 1.3 %.
     */
    fun soften(pcm: FloatArray, sr: Int): FloatArray {
        if (pcm.isEmpty()) return pcm
        val out = stretch(pcm, MALE_STRETCH)
        shelf(out, sr, MALE_SHELF_HZ, MALE_SHELF_DB)
        level(out, 1f + MALE_LEVEL)
        return out
    }

    /** Reads the buffer through a cubic interpolator at [step], so rate and pitch move together. */
    private fun stretch(pcm: FloatArray, step: Float): FloatArray {
        val out = FloatArray(max(1, (pcm.size / step).toInt()))
        for (i in out.indices) {
            val p = i * step
            val i1 = p.toInt().coerceIn(0, pcm.size - 1)
            out[i] = catmull(pcm, i1, p - i1)
        }
        return out
    }

    /**
     * RBJ high shelf at [fcHz], S = 1, in place. A shelf was chosen over a one-pole lowpass
     * because a one-pole asked for "a little softer" rolls off everything above ~1 kHz and puts
     * the voice underwater - the exact dullness this pack was re-tuned to escape. A shelf moves
     * only what is above the corner, and leaves the 1-3 kHz band that carries consonants alone.
     */
    private fun shelf(pcm: FloatArray, sr: Int, fcHz: Float, db: Float) {
        if (db == 0f || pcm.isEmpty()) return
        val a = Math.pow(10.0, db / 40.0)
        val w0 = 2.0 * PI * fcHz / sr
        val cw = cos(w0); val sw = sin(w0)
        val alpha = sw / 2.0 * sqrt(2.0)
        val sa = sqrt(a)
        val b0 = a * ((a + 1) + (a - 1) * cw + 2 * sa * sw)
        val b1 = -2 * a * ((a - 1) + (a + 1) * cw)
        val b2 = a * ((a + 1) + (a - 1) * cw - 2 * sa * sw)
        val a0 = (a + 1) - (a - 1) * cw + 2 * sa * sw
        val a1 = 2 * ((a - 1) - (a + 1) * cw)
        val a2 = (a + 1) - (a - 1) * cw - 2 * sa * sw
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        for (i in pcm.indices) {
            val x0 = pcm[i].toDouble()
            val y0 = (b0 / a0) * x0 + (b1 / a0) * x1 + (b2 / a0) * x2 -
                (a1 / a0) * y1 - (a2 / a0) * y2
            x2 = x1; x1 = x0; y2 = y1; y1 = y0
            pcm[i] = y0.toFloat()
        }
    }

    /** Gain with a ceiling: an emotion must never cost a clipped sibilant. */
    private fun level(pcm: FloatArray, g: Float) {
        var peak = 0f
        for (i in pcm.indices) {
            pcm[i] *= g
            peak = max(peak, abs(pcm[i]))
        }
        if (peak > CEILING) {
            val k = CEILING / peak
            for (i in pcm.indices) pcm[i] *= k
        }
    }

    /** Catmull-Rom through the four samples around [i], at position [i] + [mu]. */
    private fun catmull(x: FloatArray, i: Int, mu: Float): Float {
        val p0 = x[(i - 1).coerceAtLeast(0)]
        val p1 = x[i]
        val p2 = x[(i + 1).coerceAtMost(x.size - 1)]
        val p3 = x[(i + 2).coerceAtMost(x.size - 1)]
        val m1 = 0.5f * (p2 - p0)
        val m2 = 0.5f * (p3 - p1)
        val mu2 = mu * mu
        val mu3 = mu2 * mu
        return ((p1 - p2) * 2f + m1 + m2) * mu3 +
            ((p2 - p1) * 3f - m1 * 2f - m2) * mu2 + m1 * mu + p1
    }

    /** A turn this short has no prosody to read; claiming one would be a guess. */
    private const val MIN_VOICED = 12

    // Anchors from the `prosody` audit over the 19 bundled reference clips - real speakers, not a
    // model's idea of neutral. They sit at the quiet end of that set on purpose: a reference clip
    // is an expressive read, and field speech on a handset microphone is not, so anchoring to the
    // quarter of the pack that moves least is what keeps an ordinary transmission near zero
    // instead of lifting every reply. Measured over the pack: spread 0.27-0.96 with a lower
    // quartile of 0.35, density 0.54-0.82 with 0.62, dynamics 1.57-3.36 with 1.95.
    private const val SPREAD_MID = 0.35f
    private const val SPREAD_SPAN = 0.25f
    private const val DENSITY_MID = 0.62f
    private const val DENSITY_SPAN = 0.16f
    private const val DYNAMICS_MID = 1.95f
    private const val DYNAMICS_SPAN = 1.20f

    /** Kept under 1 so the axis can never reach its own extremes on one loud sentence. */
    private const val GAIN = 0.75f

    // ±10 % in pace and pitch: about 1.7 semitones at the extreme, which listeners hear as "said
    // differently" rather than as a different person. Measured with the `tone` debug scenario.
    private const val PITCH_SPAN = 0.10f

    /** ±3 dB of brightness above 2.6 kHz, which is the buzz band and not the speech band. */
    private const val TILT_HZ = 2600f
    private const val TILT_DB = 3.0f
    private const val LEVEL_SPAN = 0.12f
    private const val CEILING = 0.98f

    // The male trim: read 6 % slower through the buffer (which lowers the register by the same
    // amount and calms the pace), 2 dB off everything above 3.2 kHz, and a shade quieter. The
    // corner and the depth were both measured, not chosen: at 2.4 kHz and -3.2 dB the shelf ate
    // the 1-3 kHz band the prompt keeps (7.1 % to 5.9 %), which trades buzz for mush.
    private const val MALE_STRETCH = 0.943f
    private const val MALE_SHELF_HZ = 3200f
    private const val MALE_SHELF_DB = -2.0f
    private const val MALE_LEVEL = -0.04f
}
