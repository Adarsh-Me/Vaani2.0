package com.itantra.walkie.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * A position is the one number in this product that a rescue team acts on, so the bytes that carry
 * it are pinned: a fix that decodes to the wrong hemisphere, or a stale buffer that decodes to
 * *some* fix, sends a boat to the wrong village.
 */
class FixTest {

    private val delhi = Fix((28.6139 * 1e6).toInt(), (77.2090 * 1e6).toInt(), 8, System.currentTimeMillis())

    @Test
    fun aFixSurvivesTheWireExactly() {
        val bytes = delhi.encode(ageS = 12)
        assertEquals("11 bytes on air", 11, bytes.size)
        val back = Fix.decode(bytes)!!
        assertEquals("lat to the millionth", delhi.latE6, back.latE6)
        assertEquals("lon to the millionth", delhi.lonE6, back.lonE6)
        assertEquals(8, back.accM)
        // The age is carried, not the absolute clock: two handsets with unsynchronised times must
        // still agree on how old a fix is.
        assertTrue("age ${back.ageS()}s", back.ageS() in 10..20)
    }

    @Test
    fun garbageNeverBecomesAPosition() {
        assertNull(Fix.decode(ByteArray(5)))
        assertNull("all-zero is not a place", Fix.decode(ByteArray(11)))
        val far = delhi.encode(0).also { it[0] = 127.toByte(); it[1] = (-1).toByte() }
        assertNull("latitude beyond 90°", Fix.decode(far))
        val southern = Fix((-34_000_000), 150_000_000, 20, System.currentTimeMillis()).encode(3)
        assertEquals("a real southern-hemisphere fix still decodes", -34, Fix.decode(southern)!!.latE6 / 1_000_000)
    }

    /** One degree of latitude is ~111.19 km. Anything further off means the formula is wrong. */
    @Test
    fun groundDistanceMatchesTheGeometry() {
        val north = Fix((29.6139 * 1e6).toInt(), (77.2090 * 1e6).toInt(), 8, System.currentTimeMillis())
        val km = delhi.distanceM(north) / 1000.0
        assertTrue("1° of latitude read $km km", abs(km - 111.19) < 0.6)
        assertEquals("a phone beside itself", 0.0, delhi.distanceM(delhi), 0.001)
    }

    @Test
    fun accuracyIsCappedRatherThanOverflowingTheByte() {
        val bad = Fix(delhi.latE6, delhi.lonE6, 90_000, System.currentTimeMillis())
        assertEquals(255, Fix.decode(bad.encode(1))!!.accM)
    }
}
