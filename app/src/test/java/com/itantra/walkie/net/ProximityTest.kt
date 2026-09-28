package com.itantra.walkie.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The console is allowed to say "near" and "getting closer". It is not allowed to say metres.
 * These pin where that line is, because the temptation to print the number is permanent and the
 * reading behind it is not.
 */
class ProximityTest {

    @Test
    fun bandsFollowTheSameThresholdsAsTheSignalBars() {
        assertEquals(4, Proximity.band(-55))
        assertEquals(3, Proximity.band(-65))
        assertEquals(2, Proximity.band(-75))
        assertEquals(1, Proximity.band(-85))
        assertEquals(0, Proximity.band(-99))
        // Monotonic: a stronger reading never lands in a weaker band.
        var last = -1
        for (r in -100..-30) {
            val b = Proximity.band(r)
            assertTrue("band went backwards at $r", b >= last)
            last = b
        }
    }

    @Test
    fun oneReadingGivesNoDirection() {
        assertEquals(Trend.UNKNOWN, Proximity.trend(listOf(-70)))
        assertEquals(Trend.UNKNOWN, Proximity.trend(emptyList()))
        assertEquals(Trend.UNKNOWN, Proximity.trend(listOf(-70, -68, -66)))
    }

    @Test
    fun walkingTowardsAndAwayReadsCorrectly() {
        assertEquals(Trend.CLOSER, Proximity.trend(listOf(-92, -90, -84, -78, -72, -66)))
        assertEquals(Trend.FARTHER, Proximity.trend(listOf(-60, -63, -70, -76, -83, -90)))
    }

    /**
     * The case that matters. Real scan results wobble by 5-15 dB with nobody moving, so a trend
     * that flips on every sample would be noise dressed as navigation - and a person following it
     * walks in circles.
     */
    @Test
    fun ordinaryJitterReadsAsSteady() {
        assertEquals(Trend.STEADY, Proximity.trend(listOf(-70, -74, -68, -73, -69, -72, -67, -71)))
        assertEquals(Trend.STEADY, Proximity.trend(List(8) { -70 }))
    }

    @Test
    fun everyBandHasAWord() {
        for (b in 0..4) assertTrue("band $b has no word", Proximity.word(b).isNotBlank())
        assertEquals("at the edge", Proximity.word(-3))   // clamped, never thrown
        assertEquals("beside you", Proximity.word(9))
    }
}
