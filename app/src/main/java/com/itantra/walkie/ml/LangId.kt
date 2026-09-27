package com.itantra.walkie.ml

import com.itantra.walkie.Lang

/**
 * Which language a transcript is in, read off the transcript itself.
 *
 * This is possible at all because the microphone's model has no language token: SraVaani decodes
 * all 65 of its languages through one graph, so the app never had to be told what was spoken to
 * get words out - it only used to be told, so that the words could be re-based into their own
 * script and handed to the right translation pair. Language identification is therefore a
 * question about the *text*, not about the audio, and it costs nothing to answer.
 *
 * Two signals, in the order they should be trusted:
 *
 * 1. **Script.** Eight of the app's ten Indic languages write in a Unicode block that belongs to
 *    them alone - বাংলা, ગુજરાતી, ਪੰਜਾਬੀ, ଓଡ଼ିଆ, தமிழ், తెలుగు, ಕನ್ನಡ, മലയാളം. A code point
 *    range settles those; no model, no threshold, no error to measure.
 * 2. **Lexicon.** Devanagari is shared, and the sharing is exactly the app's most common pair:
 *    हिन्दी and मराठी are written identically at the character level. What separates them is
 *    function words - आहे/नाही/म्हणजे/झाले are Marathi and never Hindi, है/नहीं/कहते/हुआ are
 *    the reverse - so this counts markers rather than guessing from letter shapes.
 *
 * Deliberately *not* here: any claim to tell Hindi from Marathi on a two-word sentence. The
 * margin is returned so the caller can refuse to decide when the evidence is thin, and the
 * MT-score path in [LangId.MtScore] exists to be measured against this one rather than to
 * replace it.
 */
object LangId {

    /** How a guess was reached, because a script hit and a one-word lexicon hit are not equal. */
    enum class How { SCRIPT, LEXICON, NONE }

    data class Guess(val lang: Lang, val how: How, val margin: Int = 0)

    /** The blocks that belong to exactly one of the app's languages. */
    private val blocks: List<Pair<IntRange, Lang>> = listOf(
        0x0980..0x09FF to Lang.BN,   // বাংলা
        0x0A00..0x0A7F to Lang.PA,   // ਪੰਜਾਬੀ
        0x0A80..0x0AFF to Lang.GU,   // ગુજરાતી
        0x0B00..0x0B7F to Lang.OR,   // ଓଡ଼ିଆ
        0x0B80..0x0BFF to Lang.TA,   // தமிழ்
        0x0C00..0x0C7F to Lang.TE,   // తెలుగు
        0x0C80..0x0CFF to Lang.KN,   // ಕನ್ನಡ
        0x0D00..0x0D7F to Lang.ML,   // മലയാളം
    )
    private const val DEVA_START = 0x0900
    private const val DEVA_END = 0x097F

    /** Devanagari letters only. U+0964/U+0965 are the danda full stops, which Punjabi and
     *  Bengali text use too - counting them as Devanagari made every Punjabi clip ambiguous. */
    fun isDevanagariLetter(cp: Int) = cp in DEVA_START..DEVA_END && cp != 0x0964 && cp != 0x0965

    /**
     * The language implied by the letters alone, or null when the text is in Devanagari (or
     * mixed) and has to be settled by words. Counts per block rather than "first character
     * seen", because a sentence can carry a Latin loanword or a ₹ and still be unambiguous.
     */
    fun byScript(text: String): Lang? {
        val counts = HashMap<Lang, Int>()
        var deva = 0
        var latin = 0
        for (cp in text.codePoints().toArray()) {
            if (isDevanagariLetter(cp)) { deva++; continue }
            if (cp in 0x0041..0x005A || cp in 0x0061..0x007A) { latin++; continue }
            for ((r, l) in blocks) if (cp in r) {
                counts[l] = (counts[l] ?: 0) + 1
                break
            }
        }
        val top = counts.maxByOrNull { it.value }
        return when {
            // Any letter of a block that belongs to one language decides it outright; the other
            // blocks it might also touch are punctuation and stray loanwords.
            top != null && deva == 0 -> top.key
            top != null && top.value >= deva * 3 -> top.key
            deva == 0 && latin > 0 && top == null -> Lang.EN
            else -> null
        }
    }

    /**
     * Marathi markers, as word prefixes so the inflected forms (आहेत, म्हणालो, झाली) are caught
     * by one entry each. Every shared form is deliberately absent: हो, ठीक, सब, जरा, छो and
     * वेळ-style वे belong to both languages, and a marker that fires in Hindi costs the
     * distinction more than it buys.
     */
    private val MARATHI = listOf(
        "आहे", "आहो", "आहा", "नाही", "नको", "म्हण", "म्हट", "झाल", "आम्ही", "तुम्ही", "तुझ",
        "माझ", "इथ", "येथ", "तिथ", "खूप", "काय", "कस", "आता", "पण", "करणा", "गेल",
    )

    /** Hindi markers, held to the same rule. */
    private val HINDI = listOf(
        "है", "हैं", "हूँ", "हुआ", "हुई", "हुए", "नहीं", "कह", "बहुत", "लेकिन", "क्यों",
        "यह", "वह", "इस", "उस", "कैस", "मेरा", "हमारा", "तुम्ह", "आपका", "रहा", "रही",
        "गया", "गई", "कुछ", "अच्छ", "लगत", "पहल", "करत", "सकत", "चाहिय", "द्वार",
    )

    /**
     * Words of the text. Marks - matras, viramas, anusvaras - are `\p{M}` and not `\p{L}`, so a
     * letter-only split shreds "म्हणाला" into meaningless fragments and no Marathi word ever
     * matches its own prefix. Measured the hard way on the bundled transcripts.
     */
    private fun tokens(text: String): List<String> =
        text.split(Regex("[^\\p{L}\\p{M}\\p{N}]+")).filter { it.isNotBlank() }

    /** (hindi markers matched, marathi markers matched). */
    fun devaMarkers(text: String): Pair<Int, Int> {
        val w = tokens(text)
        var hi = 0
        var mr = 0
        for (t in w) {
            if (HINDI.any { t.startsWith(it) }) hi++
            if (MARATHI.any { t.startsWith(it) }) mr++
        }
        return hi to mr
    }

    /**
     * Hindi or Marathi from the function words, with how far apart the two scores were. A margin
     * of zero means the sentence carried nothing that distinguishes them - two-word turns like
     * "ठीक हूँ" really are undecidable from text - and [How.NONE] says so instead of inventing an
     * answer. A caller that shows a language it could not read is worse than one that asks.
     */
    fun byLexicon(text: String, fallback: Lang = Lang.HI): Guess {
        val (hi, mr) = devaMarkers(text)
        val margin = hi - mr
        return when {
            margin > 0 -> Guess(Lang.HI, How.LEXICON, margin)
            margin < 0 -> Guess(Lang.MR, How.LEXICON, -margin)
            else -> Guess(fallback, How.NONE, 0)
        }
    }

    /**
     * The full read. [Guess.how] is NONE only when there was no script to trust and no word to
     * count, in which case [Guess.lang] is the caller's own default rather than a finding.
     */
    fun detect(text: String, fallback: Lang = Lang.HI): Guess {
        if (text.isBlank()) return Guess(fallback, How.NONE)
        byScript(text)?.let { return Guess(it, How.SCRIPT) }
        return byLexicon(text, fallback)
    }
}
