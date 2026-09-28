package com.itantra.walkie.net

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
 * - **a band** - beside you / near / in range / far / at the edge - from the same thresholds the
 *   signal bars already use, so the word and the bars can never disagree;
 * - **a trend** - whether the last few samples are going up or down, which is what a person
 *   walking actually needs, and which is far more reliable in one direction than the other: the
 *   level is noisy, the *slope over eight samples* is not nearly as much.
 *
 * Both need several samples. One reading gives no trend at all, and says so.
 */
enum class Trend { CLOSER, FARTHER, STEADY, UNKNOWN }

object Proximity {

    /** Same dBm steps as `signalOf`, so a bar count and its word are one fact, not two. */
    fun band(rssi: Int): Int = when {
        rssi >= -61 -> 4
        rssi >= -71 -> 3
        rssi >= -81 -> 2
        rssi >= -91 -> 1
        else -> 0
    }

    private val words = listOf("at the edge", "far", "in range", "near", "beside you")
    fun word(band: Int): String = words[band.coerceIn(0, words.lastIndex)]

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
        fun median(v: List<Int>): Double {
            val s = v.sorted()
            val m = s.size / 2
            return if (s.size % 2 == 1) s[m].toDouble() else (s[m - 1] + s[m]) / 2.0
        }
        val older = median(samples.subList(0, half))
        val newer = median(samples.subList(half, samples.size))
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
