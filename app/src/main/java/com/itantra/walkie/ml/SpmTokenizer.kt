package com.itantra.walkie.ml


/**
 * Fairseq-style dict ({token: id}) + BPE merges (sp_bpe_ranks {piece: rank}).
 * Encode: SentencePiece-ish pretokenize (▁-prefix words) + greedy rank merges.
 */
class SpmTokenizer(private val res: Bundled) {
    private fun load(name: String): String = res.text("$DIR/$name")

    private val tok2id: Map<String, Int> = parseFlat(load("dict.SRC.json"))
    private val tgtId2tok: Map<Int, String> = try {
        parseFlat(load("dict.TGT.json")).entries.associate { (k, v) -> v to k }
    } catch (_: Exception) { emptyMap() }
    private val ranks: Map<String, Int> = parseFlat(load("sp_bpe_ranks.json"))
    private val tgt2id: Map<String, Int> = try {
        parseFlat(load("dict.TGT.json"))
    } catch (_: Exception) { emptyMap() }

    private companion object {
        const val DIR = "models/translation/indic-indic-dist-320M"
    }

    val bos = 0; val pad = 1; val eos = 2; val unk = 3

    fun langId(tag: String): Int = tok2id[tag] ?: tgt2id[tag] ?: unk

    fun encode(text: String): List<Int> {
        val out = ArrayList<Int>()
        for (word in text.trim().split(Regex("\\s+"))) {
            if (word.isEmpty()) continue
            // SentencePiece: first piece of word gets ▁ prefix
            val pieces = word.mapIndexed { i, c -> (if (i == 0) "▁" else "") + c }.toMutableList()
            while (true) {
                var bestRank = Int.MAX_VALUE; var bestAt = -1
                for (i in 0 until pieces.size - 1) {
                    val r = ranks[pieces[i] + pieces[i + 1]] ?: continue
                    if (r < bestRank) { bestRank = r; bestAt = i }
                }
                if (bestAt < 0) break
                pieces[bestAt] = pieces[bestAt] + pieces[bestAt + 1]
                pieces.removeAt(bestAt + 1)
            }
            for (p in pieces) out += tok2id[p] ?: unk
        }
        return out
    }

    fun decode(ids: List<Int>): String {
        val sb = StringBuilder()
        for (id in ids) {
            if (id == eos || id == pad || id == bos) continue
            sb.append(tgtId2tok[id] ?: "")
        }
        return sb.toString().replace("▁", " ").trim().replace(Regex(" +"), " ")
    }


    /** Flat {"k":int} JSON without a JSON lib (keys may contain any unicode except quote/backslash). */
    private fun parseFlat(s: String): Map<String, Int> {
        val m = LinkedHashMap<String, Int>()
        var i = 0
        while (i < s.length) {
            if (s[i] != '"') { i++; continue }
            val sb = StringBuilder(); i++
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) { sb.append(s[i + 1]); i += 2; continue }
                if (c == '"') break
                sb.append(c); i++
            }
            i++ // past quote
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r' || s[i] == ':')) i++
            var neg = false
            if (i < s.length && s[i] == '-') { neg = true; i++ }
            var v = 0
            while (i < s.length && s[i] in '0'..'9') { v = v * 10 + (s[i] - '0'); i++ }
            m[sb.toString()] = if (neg) -v else v
        }
        return m
    }
}

class SpmBpe
class IndicProcessor
