package com.itantra.walkie.ml

/**
 * Fixed rescue phrases for the moments a keyboard and a microphone both fail: waist-deep water,
 * a hand shaking, noise loud enough that no recogniser hears anything. One tap sends a whole
 * sentence.
 *
 * **The source text is Hindi, deliberately.** Every phrase rides the same path a spoken sentence
 * already rides - source words on the air, each receiving phone translating into its own
 * language with IndicTrans2 and speaking it with IndicF5. Authoring these six sentences in eleven
 * languages would have put eleven hand-written translations on the wire, and this project does
 * not claim translation quality it has not measured; the MT path, at least, has a measured number
 * behind it per pair. Hindi is the pivot the model was checked on, and the Devanagari-centric
 * dictionary is strongest there.
 *
 * The one-byte [code] is not on the wire yet. It exists for the SOS re-broadcast, where the same
 * sentence going out every few seconds through a crowded relay is the one place a fixed code
 * earns its keep over 40 coded bytes; until that is measured, the phrase itself is what travels,
 * so a receiver running any build reads the same words.
 */
enum class Preset(val code: Int, val label: String, val hindi: String) {

    /** The headline call: someone is trapped and needs extraction. */
    HELP(1, "Help, trapped here", "मदद करें, हम यहाँ फँसे हुए हैं।"),

    /** Changes what a rescue team brings, and in what order it leaves. */
    CHILDREN(2, "Children and elderly here", "हमारे पास बच्चे और बुजुर्ग हैं।"),

    /** Triage signal: this household outranks the dry one two roofs away. */
    INJURED(3, "Someone is injured", "यहाँ कोई घायल है।"),

    /** Turns "come soon" into "come before tomorrow". */
    WATER(4, "No water left", "हमारे पास पानी नहीं बचा है।"),

    /** Where to look. In a flood this is the difference between a search and a guess. */
    ROOF(5, "We are on the roof", "हम छत पर हैं।"),

    /** As important as the call: stops a boat coming twice while it is needed elsewhere. */
    CLEAR(6, "Everyone safe, do not come", "सब सुरक्षित हैं, यहाँ आने की ज़रूरत नहीं।");

    companion object {
        /**
         * The distress loop. Slow enough that a handset relaying for a dozen neighbours is not
         * drowned in its own beacon, fast enough that a team watching for it does not conclude the
         * battery died. Repeats until cancelled or [SOS_LIMIT_MS] passes, after which the phone
         * stops shouting on its own and says why.
         */
        const val SOS_REPEAT_MS = 12_000L
        const val SOS_LIMIT_MS = 10L * 60L * 1000L

        /** A relayed SOS is the one message worth spending the extra hop on. */
        const val SOS_HOPS = 4
    }
}
