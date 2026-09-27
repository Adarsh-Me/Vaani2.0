package com.itantra.walkie.ml

import com.itantra.walkie.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Auto-detect has to be measured on the sentences this app actually carries, not on examples
 * picked to make it pass. Every reference transcript ships with its language in the filename, so
 * this reads all of them, asks [LangId] what language each one is, and prints the whole table -
 * including the Devanagari rows, where the letters cannot help and only the function words can.
 *
 * The host can only judge the words. The audio leg - did the microphone transcribe a Marathi
 * sentence as Marathi words at all - is measured on the handset by `--es debug langid`.
 */
class LangIdTest {

    private val rows: List<Pair<Lang, String>> by lazy {
        (refsDir().listFiles { f -> f.isFile && f.extension == "txt" } ?: emptyArray())
            .sortedBy { it.name }
            .mapNotNull { f ->
                val text = f.readText(Charsets.UTF_8).trim()
                if (text.isEmpty()) return@mapNotNull null
                val code = f.nameWithoutExtension.removePrefix("ref_").substringBefore('_')
                val lang = Lang.values().firstOrNull { it.name.equals(code, true) }
                lang?.let { it to text }
            }
    }

    @Test
    fun scriptNamesEveryLanguageWhoseBlockIsItsOwn() {
        assertTrue("no transcripts found at ${refsDir()}", rows.isNotEmpty())
        val report = StringBuilder()
        var judged = 0
        for ((lang, text) in rows) {
            val got = LangId.byScript(text)
            if (lang == Lang.HI || lang == Lang.MR) {
                assertNull("${lang.name} must not be settled by script - Devanagari is shared: $text", got)
            } else {
                judged++
                assertEquals("script of $lang (${text.take(24)}…)", lang, got)
            }
            report.append("  ${lang.name}: script=${got?.name ?: "-"}\n")
        }
        println("LID-SCRIPT\n$report  judged=$judged of ${rows.size} clips, devanagari refused by design")
    }

    /**
     * The hard pair. Hindi and Marathi are written with the same characters, so this is the only
     * place the app has to read words - and the only place a wrong answer silently mistranslates.
     *
     * Only the clips actually set in Devanagari are judged here. `ref_hi` is a Hindi sentence
     * written in Nastaliq, which is a real thing this corpus contains, and the honest answer for
     * it is "I cannot tell from these letters" - so it is asserted separately, as a refusal.
     */
    @Test
    fun devanagariIsToldApartByItsFunctionWords() {
        val deva = rows.filter { (lang, text) ->
            (lang == Lang.HI || lang == Lang.MR) && text.any { LangId.isDevanagariLetter(it.code) }
        }
        assertTrue("no Devanagari clips to test", deva.size >= 4)
        var right = 0
        val report = StringBuilder()
        for ((lang, text) in deva) {
            val g = LangId.byLexicon(text, fallback = Lang.HI)
            val (hi, mr) = LangId.devaMarkers(text)
            if (g.lang == lang && g.how != LangId.How.NONE) right++
            report.append("  $lang → ${g.lang.name} (${g.how}, margin=${g.margin}) hi=$hi mr=$mr  «$text»\n")
        }
        println("LID-DEVANAGARI\n$report  right=$right of ${deva.size}")
        assertEquals("every bundled Devanagari sentence must be told apart: $report", deva.size, right)
    }

    /**
     * Nastaliq Hindi: the letters belong to a language the app does not carry and the words are
     * not Devanagari, so nothing may be claimed from it. A guess here would look identical to a
     * correct answer in the UI and only show up as a bad translation three seconds later.
     */
    @Test
    fun aScriptItCannotReadIsRefusedNotGuessed() {
        val nastaliq = rows.firstOrNull { row ->
            row.second.any { c -> c.code in 0x0600..0x06FF }
        } ?: return   // no such clip in this build of the corpus; nothing to assert
        assertNull("Arabic-script text must not be settled by Brahmic blocks: ${nastaliq.second}",
            LangId.byScript(nastaliq.second))
        assertEquals("and not by Devanagari words either",
            LangId.How.NONE, LangId.byLexicon(nastaliq.second, fallback = Lang.MR).how)
    }

    /**
     * The end-to-end read over all 22 clips. The fallback is a fixed Hindi, never the clip's own
     * language: a test that scores a guess against the answer it was handed proves nothing, and
     * that is exactly what this one did the first time it was written.
     */
    @Test
    fun detectDecidesRightOrSaysItCannot() {
        var decided = 0
        var right = 0
        val wrong = ArrayList<String>()
        val refused = ArrayList<String>()
        for ((lang, text) in rows) {
            val g = LangId.detect(text, fallback = Lang.HI)
            if (g.how == LangId.How.NONE) { refused += "$lang «${text.take(28)}»"; continue }
            decided++
            if (g.lang == lang) right++ else wrong += "$lang→${g.lang.name}(${g.how}) «$text»"
        }
        println("LID-DETECT decided=$decided of ${rows.size} right=$right refused=${refused.size} $refused")
        assertTrue("nothing was decided at all", decided >= 18)
        assertEquals("auto-detect got these wrong: $wrong", decided, right)
    }

    /**
     * What the feature must not do: decide silently when there is nothing to decide from. A
     * two-word turn in either language is genuinely ambiguous, and an honest "I don't know" is
     * the behaviour the UI depends on.
     */
    @Test
    fun anUndecidableLineIsRefusedRatherThanGuessed() {
        assertEquals(LangId.How.NONE, LangId.byLexicon("जल", fallback = Lang.HI).how)
        assertEquals(LangId.How.NONE, LangId.byLexicon("", fallback = Lang.MR).how)
        assertEquals(LangId.How.NONE, LangId.byLexicon("पानी", fallback = Lang.MR).how)
        // The shared forms are markers for neither side, so each language has to say its own.
        assertEquals("है is Hindi's own", Lang.HI, LangId.byLexicon("मैं ठीक हूँ", fallback = Lang.MR).lang)
        assertEquals("आहे is Marathi's own", Lang.MR, LangId.byLexicon("मी ठीक आहे", fallback = Lang.HI).lang)
    }

    private fun refsDir(): File =
        listOf("src/main/assets/refs", "app/src/main/assets/refs").map(::File).first { it.isDirectory }
}
