package com.itantra.walkie.net

import kotlin.math.roundToInt

/**
 * How near a handset is, said the only way Bluetooth allows.
 *
 * RSSI cannot give metres. It is a raw received-power reading that a wet hand, a pocket, a wall or
 * the phone's own antenna rotation moves by 10-20 dB - and 10 dB is a factor of ten in distance.
 * A UI that prints "12 m" from it is not measuring, it is decorating, and on this product the
 * difference matters: someone walking toward a number that was never real is someone who does not
 * find the roof they were told to find.
 *
 * What the reading does support, honestly:
 * - **a level** - the median of the rolling window rather than the newest packet, because a raw
 *   sample wobbles 5-15 dB with nobody moving, and a display that wobbles with it gets ignored. The
 *   band word, the bar steps and the bar's fill are all computed from that one level, so the row
 *   cannot tell three stories about the same peer;
 * - **a band** - beside you / near / in range / far / at the edge - which the steps and the fill are
 *   cut at, so a full bar and the word `beside you` are the same claim rather than two nearby ones;
 * - **a trend** - whether the last few samples are going up or down, which is what a person
 *   walking actually needs, and which is far more reliable in one direction than the other: the
 *   level is noisy, the *slope over eight samples* is not nearly as much.
 *
 * A trend needs several samples. One reading gives none, and says so.
 */
enum class Trend { CLOSER, FARTHER, STEADY, UNKNOWN }

object Proximity {

    /** Bottom of band 1 and top of band 4: the whole span the proximity bar is drawn across. */
    private const val FAR_DBM = -91
    private const val NEAR_DBM = -61

    /**
     * The level the row is drawn from: the median of the window, so one lucky advertisement packet
     * cannot fill the meter. Null when nothing has been heard, which the caller must show as
     * "no reading" rather than as a zero.
     */
    fun smoothed(samples: List<Int>): Int? = median(samples)?.roundToInt()

    /**
     * dBm steps of the five bands. -1 and above is not a reading: it is what a radio reports when it
     * has nothing (the scan callback's own unknown marker, and the value `BleMesh` stores when an
     * advertisement arrived without one), and no over-the-air BLE sample is ever that strong. It
     * lands in the weakest band, because "no reading" dressed up as "beside you" is the one mistake
     * that sends someone walking toward a phone that is not there.
     */
    fun band(rssi: Int): Int = when {
        rssi >= -1 -> 0
        rssi >= NEAR_DBM -> 4
        rssi >= -71 -> 3
        rssi >= -81 -> 2
        rssi >= FAR_DBM -> 1
        else -> 0
    }

    /** Three steps is the most a meter of this size carries; five bands would be noise. */
    fun bars(band: Int): Int = when (band.coerceIn(0, 4)) {
        4 -> 3
        3, 2 -> 2
        else -> 1
    }

    private val words = listOf("at the edge", "far", "in range", "near", "beside you")
    fun word(band: Int): String = words[band.coerceIn(0, words.lastIndex)]

    /**
     * 0f at the edge of range, 1f at `beside you`: the level placed across the same span [band]
     * divides, so the drawing and the word under it are one reading.
     *
     * It is a *relative* meter and carries no number on purpose. It answers "am I getting warmer",
     * which is what the signal supports, and not "how far", which it does not.
     */
    fun fill(rssi: Int): Float {
        if (rssi >= -1) return 0f
        return (((rssi - FAR_DBM).toDouble() / (NEAR_DBM - FAR_DBM)).coerceIn(0.0, 1.0)).toFloat()
    }

    private fun median(v: List<Int>): Double? {
        if (v.isEmpty()) return null
        val s = v.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m].toDouble() else (s[m - 1] + s[m]) / 2.0
    }

    /**
     * Median of the older half of the window against the median of the newer half, with a 4 dB dead
     * band. The halves must be split in **time order** and only sorted inside themselves: sort the
     * whole window first and you compare the weak readings against the strong ones, which reports
     * "closer" no matter which way the handset is actually moving. The dead band is the other half
     * of the trick - under it the two halves differ by less than one sample's own jitter, and the
     * honest answer is "steady", not a confident arrow.
     */
    fun trend(samples: List<Int>): Trend {
        if (samples.size < 4) return Trend.UNKNOWN
        val half = samples.size / 2
        val older = median(samples.subList(0, half)) ?: return Trend.UNKNOWN
        val newer = median(samples.subList(half, samples.size)) ?: return Trend.UNKNOWN
        return when {
            newer - older >= 4.0 -> Trend.CLOSER
            older - newer >= 4.0 -> Trend.FARTHER
            else -> Trend.STEADY
        }
    }

    fun arrow(t: Trend): String = when (t) {
        Trend.CLOSER -> "↑ closer"
        Trend.FARTHER -> "↓ farther"
        Trend.STEADY -> "→ steady"
        Trend.UNKNOWN -> "listening…"
    }
}
