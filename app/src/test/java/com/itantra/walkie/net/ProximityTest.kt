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

    /**
     * -1 is what this codebase stores when the radio reported nothing. A reading that does not exist
     * must not come out as "beside you", which is the failure mode that sends someone toward a phone
     * that is not there.
     */
    @Test
    fun aMissingReadingIsNeverTheStrongestOne() {
        assertEquals(0, Proximity.band(-1))
        assertEquals(0, Proximity.band(0))
        assertEquals(0f, Proximity.fill(-1), 0f)
        assertNull(Proximity.smoothed(emptyList()))
    }

    @Test
    fun barStepsComeOutOfTheSameBands() {
        assertEquals(3, Proximity.bars(4))
        assertEquals(2, Proximity.bars(3))
        assertEquals(2, Proximity.bars(2))
        assertEquals(1, Proximity.bars(1))
        assertEquals(1, Proximity.bars(0))
        // A weaker band can never show more steps.
        var last = -1
        for (b in 0..4) {
            val s = Proximity.bars(b)
            assertTrue("steps went backwards at band $b", s >= last)
            last = s
        }
    }

    /**
     * The bar is drawn across the span the bands divide, so a full bar means `beside you` and an
     * empty one means the edge of range - the drawing and the words cannot tell two stories.
     */
    @Test
    fun fillSpansTheBandThresholds() {
        assertEquals(0f, Proximity.fill(-91), 0.001f)
        assertEquals(1f, Proximity.fill(-61), 0.001f)
        assertEquals(0.5f, Proximity.fill(-76), 0.001f)
        assertEquals(1f, Proximity.fill(-40), 0.001f)   // saturates, never overflows
        assertEquals(0f, Proximity.fill(-120), 0.001f)
        for (r in -120..-2) {
            val f = Proximity.fill(r)
            if (f == 1f) assertEquals("full bar at $r is not the top band", 4, Proximity.band(r))
            if (f == 0f) assertTrue("empty bar at $r reads as in range", Proximity.band(r) <= 1)
        }
    }

    /**
     * The reason the row takes the window and not the newest packet: one strong advertisement must
     * not fill the meter, or the meter reads as motion whenever the radio happens to be lucky.
     */
    @Test
    fun theRowReadsTheWindowNotTheNewestPacket() {
        val jittered = listOf(-70, -74, -68, -73, -69, -72, -67, -71)
        val level = Proximity.smoothed(jittered)!!
        assertTrue("jitter moved the level by ${kotlin.math.abs(level + 70)} dB",
            kotlin.math.abs(level - -70) <= 3)
        // Walking up close: the meter has to rise, or it is decoration.
        val walking = listOf(-92, -90, -84, -78, -72, -66)
        assertTrue(
            "the meter did not rise as the handset came closer",
            Proximity.fill(Proximity.smoothed(walking)!!) >
                Proximity.fill(Proximity.smoothed(listOf(-92, -90, -88, -86, -84, -82))!!)
        )
        // One lucky packet at the end of a distant window stays a distant window.
        val lucky = listOf(-92, -90, -88, -86, -84, -60)
        assertTrue(
            "one packet filled the meter",
            Proximity.fill(Proximity.smoothed(lucky)!!) < 0.35f
        )
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
