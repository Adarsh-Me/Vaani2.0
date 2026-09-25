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
}
