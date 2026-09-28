package com.itantra.walkie.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These six sentences are the whole point of the rescue path, so the failure mode worth testing is
 * the boring one: an entry edited to empty, or two presets given the same code. A blank line does
 * not look broken in the UI - the send simply returns early and the person tapping it hears
 * nothing back.
 */
class PresetsTest {

    @Test
    fun everyPresetCarriesARealSentence() {
        for (p in Preset.values()) {
            assertTrue("${p.name} has no words", p.hindi.isNotBlank())
            assertTrue("${p.name} has no label", p.label.isNotBlank())
            assertTrue("${p.name} must be a full sentence", p.hindi.length > 12)
            // The pivot is Devanagari: the MT dictionary this build was measured against is
            // centred on it, which is why the phrases are authored in Hindi at all.
            assertTrue(
                "${p.name} must be written in Devanagari",
                p.hindi.any { it.code in 0x0900..0x097F }
            )
        }
    }

    @Test
    fun codesAreUniqueAndTheSOSLineExists() {
        val codes = Preset.values().map { it.code }
        assertEquals("duplicate preset code", codes.size, codes.toSet().size)
        assertEquals(Preset.HELP, Preset.values().first { it.code == 1 })
    }

    /** A preset is sent as ordinary text, so it has to survive the wire codec like any sentence. */
    @Test
    fun presetsAreShortEnoughForOneBluetoothBurst() {
        for (p in Preset.values()) {
            val bytes = p.hindi.toByteArray(Charsets.UTF_8).size
            assertTrue("${p.name} weighs $bytes bytes", bytes <= 120)
        }
    }

    @Test
    fun theSOSWindowIsStatedInRealUnits() {
        // Slow enough not to drown a relay, fast enough that silence is not mistaken for rescue,
        // and capped so a rescued phone stops shouting on its own.
        assertEquals(12_000L, Preset.SOS_REPEAT_MS)
        assertTrue("SOS window must end", Preset.SOS_LIMIT_MS in 60_000L..30L * 60_000L)
        assertTrue("SOS must out-relay an ordinary line", Preset.SOS_HOPS > 3)
    }
}
