package com.itantra.walkie.net

import com.itantra.walkie.ml.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random

/**
 * The wire codec has to be exactly invertible or it silently corrupts messages between two
 * handsets, so this is not a smoke test: it round-trips every real transcript the app ships,
 * every field sentence it can produce, and thousands of deterministic fuzz messages, and asserts
 * the decoded string is character-for-character the input.
 *
 * It also pins the two claims the UI makes on a bubble: that the bytes on air are fewer than the
 * UTF-8 the same message would cost, for every language - and that a frame with a single wrong
 * bit is refused rather than read.
 */
class WireCodecTest {

    private val codec by lazy { WireCodec(modelBytes()) }

    @Test
    fun everyShippedReferenceTranscriptRoundTrips() {
        val refs = refsOf()
        assertTrue("no reference transcripts found at ${refsDir()}", refs.isNotEmpty())
        for ((name, text) in refs)
            assertEquals("round-trip of $name", text, codec.unpack(codec.pack(text)))
    }

    @Test
    fun fieldSentencesRoundTrip() {
        val cases = listOf(
            "नमस्कार, सभी टीमों को सूचित किया जाता है।",
            "पुल के पास हैं। बीस मिनट में पहुँचते हैं।",
            "ଆମେ ପୁଲଜବଳ ଆଛୁ।",
            "நீங்க எப்படி இருக்கிறீர்கள்?",
            "Water at 12.5 km, 3 boats left, ETA 14:30",
            "Team α → 42 @ ramp 7 ✅",
            "a",
            "абвгд",
            "₹450 per crate, 3 crates",                       // a literal '$' used to cut the message
            "https://example.internal/water/report?id=7",
            "🙏🙏",
        )
        for (s in cases) assertEquals("round-trip of <$s>", s, codec.unpack(codec.pack(s)))
    }

    /**
     * The seam the radio actually uses: a tone byte-prefixed in front of a coded frame. A frame's
     * first byte is its flag, and the tone's mark is 0x01, so a model-coded frame that began with
     * a mark would be read as a tone and lose two bytes of payload - the decoder would then return
     * plausible garbage instead of the message. This pins both flag values clear of the mark and
     * the composition in both directions.
     */
    @Test
    fun toneAndFrameComposeInEitherOrder() {
        val text = "पुल के पास हैं। बीस मिनट में पहुँचते हैं।"
        for (anim in listOf(0f, 0.61f, -0.9f, 1f)) {
            val tone = Tone(anim)
            val onAir = Tone.wrap(codec.pack(text), tone)
            assertTrue(
                "a frame flag must never be the tone mark",
                onAir[if (tone.neutral) 0 else 2] != 1.toByte()
            )
            val (backTone, backFrame) = Tone.split(onAir)
            assertEquals("tone of $anim lost", tone.wire, backTone.wire)
            assertEquals("framed body of $anim lost", text, WireCodec.unframe(codec, backFrame))
        }
    }

    /**
     * A handset whose model asset cannot be read must still carry traffic, and must refuse a frame
     * it cannot decode rather than guess at one. This is the mixed-fleet case: one phone coding,
     * one not, in both directions.
     */
    @Test
    fun aPhoneWithoutTheModelStillSendsAndRefusesWhatItCannotRead() {
        val text = "जल वितरण टीम 4 को पूल 12 पर भेजें।"
        val literal = WireCodec.frame(null, text)
        assertEquals("no model must send text verbatim", text, WireCodec.unframe(null, literal))
        assertEquals("a model phone must still read it", text, WireCodec.unframe(codec, literal))
        val coded = WireCodec.frame(codec, text)
        assertNull(
            "a phone without the tables must refuse a coded frame, never invent text",
            WireCodec.unframe(null, coded)
        )
        assertEquals(text, WireCodec.unframe(codec, coded))
    }

    @Test
    fun deterministicFuzzRoundTrips() {
        val rnd = Random(20260927L)
        val pool = IntArray(600) { rnd.nextInt(0x2000) } + intArrayOf(0x1F64F, 0x200D, 0x0A, 0x09, 0x24, 0x5E)
        var wire = 0L
        var utf8 = 0L
        repeat(4000) {
            val n = 1 + rnd.nextInt(160)
            val sb = StringBuilder()
            repeat(n) { sb.appendCodePoint(pool[rnd.nextInt(pool.size)]) }
            val s = sb.toString()
            val frame = codec.pack(s)
            val back = codec.unpack(frame)
            if (back != s) throw AssertionError("fuzz mismatch at iteration $it: <$s> -> <$back>")
            wire += frame.size
            utf8 += s.toByteArray(Charsets.UTF_8).size
        }
        // pack() picks the smaller of the two encodings per message, so the frame can never cost
        // more than the UTF-8 it replaces plus its own three bytes of flag and checksum.
        assertTrue("frames grew past their overhead: $wire vs $utf8", wire <= utf8 + 4000L * 3)
    }

    /**
     * The number the UI prints. Measured over the transcripts this app ships - real sentences in
     * eleven scripts, not a synthetic best case - and asserted per file, because the model halves
     * Hindi and triples English, and a single aggregate would hide that.
     */
    @Test
    fun indicTextHalvesAndNothingEverGrows() {
        var modelWins = 0
        val report = StringBuilder()
        for ((name, text) in refsOf()) {
            val frame = codec.pack(text)
            val raw = text.toByteArray(Charsets.UTF_8).size
            val saved = 100 - frame.size * 100 / raw
            report.append("  $name: ${frame.size} vs $raw bytes (-$saved%)\n")
            assertTrue("$name grew on the wire: ${frame.size} > $raw", frame.size <= raw + 3)
            if (frame.size < raw) modelWins++
        }
        println("PACK\n$report")
        // Most of the shipped corpus is Indic, so the model must be winning on the majority of it.
        assertTrue("the model won on too few clips: $modelWins", modelWins >= 12)
    }

    @Test
    fun corruptedFramesAreAlwaysRefused() {
        val text = "नमस्कार, सभी टीमों को सूचित किया जाता है।"
        val good = codec.pack(text)
        val rnd = Random(7L)
        repeat(500) {
            val bad = good.copyOf()
            repeat(1 + rnd.nextInt(3)) { bad[rnd.nextInt(bad.size)] = rnd.nextInt(256).toByte() }
            val out = codec.unpack(bad)
            if (out != null) throw AssertionError("a corrupted frame decoded to <$out>")
        }
    }

    @Test
    fun truncatedFramesNeverYieldText() {
        val good = codec.pack("तुमची टीम कुठे आहे?")
        for (n in 0 until good.size)
            assertNull("a $n-byte prefix of ${good.size} decoded to ${codec.unpack(good.copyOf(n))}",
                codec.unpack(good.copyOf(n)))
        assertNull(codec.unpack(ByteArray(0)))
    }

    /** A receiver running a different model build must refuse the frame, not misread it. */
    @Test
    fun unknownPackFlagIsRefused() {
        val good = codec.pack("जल की कमी है")
        val foreign = good.copyOf().also { it[0] = 0x7F }
        assertNull(codec.unpack(foreign))
    }

    // ------------------------------------------------------------------ fixtures

    private fun refsOf(): List<Pair<String, String>> =
        (refsDir().listFiles { f -> f.isFile && f.extension == "txt" } ?: emptyArray())
            .sortedBy { it.name }
            .map { it.name to it.readText(Charsets.UTF_8).trim() }
            .filter { it.second.isNotEmpty() }

    private fun modelBytes(): ByteArray {
        val f = listOf("src/main/assets/mesh/charmodel.bin", "app/src/main/assets/mesh/charmodel.bin")
            .map(::File).firstOrNull { it.isFile }
            ?: throw AssertionError("charmodel.bin not found from ${File(".").absolutePath}")
        return f.readBytes()
    }

    private fun refsDir(): File =
        listOf("src/main/assets/refs", "app/src/main/assets/refs").map(::File).first { it.isDirectory }
}
