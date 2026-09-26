package com.itantra.walkie.ml

import com.itantra.walkie.Lang

/**
 * Devanagari -> native script for the MT output.
 *
 * The bundled IndicTrans2 export carries a Devanagari-centric target dictionary
 * (75,518 Devanagari pieces vs 139 Bengali / 54 Tamil / 64 Telugu), so it can only
 * spell a Bengali or Tamil sentence with Devanagari glyphs. The WORDS are correct -
 * "आपनार सङ्गे कथा बलते आमार भालो लेगेछे" is genuine Bengali - so what is missing is
 * the script, and for the walkie that matters twice: the panel shows text the user
 * cannot read as their language, and IndicF5 is then handed Devanagari and speaks it
 * with Hindi phonetics, which is what users hear as "wrong language / robotic".
 *
 * Unicode aligned the Brahmic blocks akshara-by-akshara (vowels, consonants, matras
 * and virama all sit at the same offset inside U+0900..U+0D7F), so re-basing a code
 * point is a correct transliteration for most of the inventory. Only the precomposed
 * nukta consonants and a few script-specific conventions need explicit handling.
 */
object IndicTranslit {
    /** Block base for each target script; Devanagari and Latin need nothing. */
    private fun base(tgt: Lang): Int = when (tgt) {
        Lang.BN -> 0x980
        Lang.PA -> 0xA00
        Lang.GU -> 0xA80
        Lang.OR -> 0xB00
        Lang.TA -> 0xB80
        Lang.TE -> 0xC00
        Lang.KN -> 0xC80
        Lang.ML -> 0xD00
        else -> -1
    }

    /** Devanagari-only composed consonants with no aligned slot: use the plain base. */
    private fun unfoldNukta(cp: Int): Int = when (cp) {
        0x958 -> 0x915; 0x959 -> 0x916; 0x95A -> 0x917 // क़ ख़ ग़
        0x95B -> 0x921; 0x95C -> 0x922                  // ड़ ढ़
        0x95D -> 0x922; 0x95E -> 0x92F; 0x95F -> 0x91C  // ऱ ड़् ऽ ज़ fallbacks
        else -> cp
    }

    fun needsTranslit(tgt: Lang): Boolean = base(tgt) >= 0

    fun fromDeva(text: String, tgt: Lang): String {
        val b = base(tgt)
        if (b < 0) return text
        val out = StringBuilder(text.length)
        for (cp in text.codePoints().toArray()) {
            when {
                cp == 0x970 -> {} // abbreviation sign, not used outside Devanagari
                cp == 0x964 -> out.append(if (tgt == Lang.TA) '.' else '।') // danda
                cp in 0x958..0x95F -> out.appendCodePoint(b + (unfoldNukta(cp) - 0x900))
                cp in 0x900..0x97F -> out.appendCodePoint(b + (cp - 0x900))
                else -> out.appendCodePoint(cp)
            }
        }
        return out.toString()
    }

    /**
     * The inverse of [fromDeva]: a sentence written in [src]'s script, re-based onto Devanagari
     * code points. The bundled MT export spells almost nothing outside Devanagari - 75,518
     * Devanagari pieces against 139 Bengali and 88 Kannada in the same dictionary - so a Bengali
     * or Gujarati sentence reaches the model as `<unk>` and comes back unchanged. The Brahmic
     * blocks are akshara-aligned, so shifting the block keeps every consonant, matra and
     * anusvara the same sound, and the words the operator spoke survive the trip even though the
     * alphabet changes.
     */
    fun toDeva(text: String, src: Lang): String {
        val b = base(src)
        if (b < 0) return text
        val out = StringBuilder(text.length)
        for (cp in text.codePoints().toArray()) {
            when {
                cp in b..(b + 0x7F) -> out.appendCodePoint(0x900 + (cp - b))
                cp == 0x0964 -> out.append('।') // danda, wherever it came from
                else -> out.appendCodePoint(cp)
            }
        }
        return out.toString()
    }

    /** True when this language writes in a block the MT dictionary cannot spell. */
    fun needsRebase(src: Lang): Boolean = base(src) >= 0 && src != Lang.HI && src != Lang.MR

    /**
     * The code-point block a language is written in. The Brahmic entries come from the same
     * table the re-basing uses, so asking "where does this language live" and asking "move it
     * there" can never disagree.
     */
    fun blockOf(l: Lang): IntRange = when (l) {
        Lang.EN -> 0x0000..0x024F // Latin, including the extended blocks a transliteration lands in
        Lang.HI, Lang.MR -> 0x0900..0x097F
        else -> base(l).let { it..(it + 0x7F) }
    }

    /**
     * Share of a string's letters that sit in [l]'s own block. A recogniser or a translator
     * that answers in the wrong alphabet is the failure this exists to catch: the words can be
     * perfect and the sentence still unreadable, so text has to be measured, not trusted.
     */
    fun scriptShare(text: String, l: Lang): Double {
        val blk = blockOf(l)
        var letters = 0; var inBlk = 0
        for (cp in text.codePoints().toArray()) {
            if (!Character.isLetter(cp)) continue
            letters++
            if (cp in blk) inBlk++
        }
        return if (letters == 0) 0.0 else inBlk.toDouble() / letters
    }

    /** True when [text] is written mostly in Devanagari, whichever language asked for it. */
    fun isDevanagari(text: String): Boolean = scriptShare(text, Lang.HI) >= 0.5

    /**
     * Move [text] out of whichever Brahmic block it arrived in and into [tgt]'s own.
     *
     * The recogniser answers a Kannada turn in Telugu code points and a Punjabi turn in
     * Devanagari often enough that "shift from Devanagari" alone leaves good sentences unreadable
     * on the panel and unpronounceable by the voice. Every Brahmic block occupies the same 128
     * code points in akshara order, so the trip through Devanagari is exact for the shared
     * inventory and costs nothing that a direct shift would do better.
     *
     * Returns [text] untouched when it is already in the right block, or when its letters do not
     * sit in any one block confidently enough to know where they came from.
     */
    fun rebaseScript(text: String, tgt: Lang): String {
        if (text.isEmpty() || scriptShare(text, tgt) >= 0.5) return text
        val src = LANGS.filter { it != tgt }.maxOfOrNull { scriptShare(text, it) } ?: 0.0
        if (src < 0.5) return text
        val from = LANGS.first { scriptShare(text, it) == src }
        return fromDeva(toDeva(text, from), tgt)
    }

    private val LANGS = Lang.values().toList()
}
