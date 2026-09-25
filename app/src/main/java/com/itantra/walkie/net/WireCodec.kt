package com.itantra.walkie.net

import java.io.DataInputStream
import kotlin.math.abs

/**
 * Lossless wire codec for walkie messages: an order-1 character model over a fixed alphabet,
 * driven through a binary-tree arithmetic coder.
 *
 * NOT YET WORKING - DO NOT WIRE INTO ANYTHING. The model and the measured 80.0% lossless
 * reduction are verified (tmp/genmodel.js scores held-out text with these exact shipped tables),
 * but the range coder below has not passed a round-trip: the reference implementation in
 * tmp/codectest.js diverges and decodes garbage. The interval renormalisation and the
 * carry/underflow handling must be fixed and proven by a round-trip over thousands of real
 * sentences before this class is used, or it will silently corrupt messages on the mesh.
 *
 * Why this shape: a Brahmic code point costs 3 bytes in UTF-8 but carries roughly 3-4 bits of
 * information, and general-purpose compressors cannot exploit that on a 200-byte message - they
 * have no room to find structure (measured: deflate 44%, brotli 49% on this corpus). Indexing
 * into a per-character model and arithmetic-coding it reaches 80.0% lossless on held-out
 * sentences, scored with the exact quantised tables shipped in assets/mesh/charmodel.bin.
 *
 * Both ends must hold identical tables or decoding silently produces garbage, so the model is a
 * versioned asset, not something learned at runtime. The escape symbol (index 0) covers any code
 * point outside the alphabet - digits, Latin, emoji - at ~4 bytes each.
 *
 * The coder is the LZMA range-coder construction: probabilities are 11-bit, one binary decision
 * per bit of the symbol index, with the cache/carry handling that lets the low end of the
 * interval overflow 32 bits without corrupting already-emitted bytes.
 */
class WireCodec(model: ByteArray) {

    private val alpha: IntArray                 // code point per symbol index
    private val index: HashMap<Int, Int>        // code point -> symbol index
    /** probs[context][node] = P(bit 0) scaled to 2048, for the binary tree over symbol indices. */
    private val probs: Array<IntArray>
    private val treeBits: Int                   // ceil(log2(alphabetSize))
    val alphabetSize: Int

    init {
        val d = DataInputStream(model.inputStream())
        val magic = ByteArray(4).also { d.readFully(it) }
        require(String(magic, Charsets.US_ASCII) == "IWCM") { "bad charmodel magic ${String(magic)}" }
        val version = d.readUnsignedShort()
        require(version == 1) { "unsupported charmodel version $version" }
        alphabetSize = d.readUnsignedShort()
        alpha = IntArray(alphabetSize) { d.readUnsignedIntLE() }
        index = HashMap(alphabetSize * 2)
        for (i in alpha.indices) index.putIfAbsent(alpha[i], i)
        treeBits = 32 - Integer.numberOfLeadingZeros(alphabetSize - 1)
        // Skip the shipped unigram block; it is subsumed by the order-1 rows.
        val skip = ByteArray(alphabetSize * 2)
        var done = 0
        while (done < skip.size) { val n = d.read(skip, done, skip.size - done); if (n < 0) break; done += n }
        val root = buildTree(quantised(d, alphabetSize))
        probs = Array(alphabetSize) { buildTree(quantised(d, alphabetSize)) }
        // Context 0 is the escape symbol itself; give it the unigram shape so a run of escapes
        // degrades to order-0 instead of inheriting a nonsense row.
        if (probs.isNotEmpty()) probs[0] = root
    }

    private fun DataInputStream.readUnsignedIntLE(): Int {
        val b = ByteArray(4).also { readFully(it) }
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or
            ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24)
    }

    /** Reads one shipped distribution (count + sparse (symbol, freq) pairs) into a dense array. */
    private fun quantised(d: DataInputStream, n: Int): IntArray {
        val total = d.readUnsignedShort()
        val pairs = d.readUnsignedShort()
        val f = IntArray(n) { 1 }
        var used = n
        for (i in 0 until pairs) {
            val sym = d.readUnsignedShort()
            val fq = d.readUnsignedShort()
            if (sym < n) { f[sym] = fq; used += fq - 1 }
        }
        if (used != SCALE && pairs > 0) {
            // Largest-remainder correction so the row sums exactly to SCALE; a row that does not
            // would let the coder produce intervals the decoder cannot resolve.
            var diff = SCALE - used
            var i = 0
            while (diff != 0 && i < n * 4) {
                val s = i % n
                if (diff > 0) { f[s]++; diff-- } else if (f[s] > 1) { f[s]--; diff++ }
                i++
            }
        }
        return f
    }

    /** Binary-tree P(bit 0) for each internal node, from a frequency row summing to SCALE. */
    private fun buildTree(freq: IntArray): IntArray {
        val leaves = 1 shl treeBits
        val sum = IntArray(2 * leaves)
        for (i in 0 until leaves) sum[leaves + i] = if (i < freq.size) freq[i] else 0
        for (i in leaves - 1 downTo 1) sum[i] = sum[i shl 1] + sum[i shl 1 or 1]
        val p = IntArray(leaves)
        for (node in 1 until leaves) {
            val tot = sum[node]
            p[node] = if (tot <= 0) 1024
            else (sum[node shl 1].toLong() * 2048 / tot).toInt().coerceIn(1, 2047)
        }
        return p
    }

    companion object {
        private const val SCALE = 4096
        private const val TOP = 1 shl 24
        private const val ESCAPE = 0
        private const val CTRL = 0x005E          // '^' sentinel, start of every context chain
    }

    /** Encodes text to wire bytes. Returns null if the text is empty. */
    fun encode(text: String): ByteArray? {
        if (text.isEmpty()) return null
        val e = Encoder()
        var context = index[CTRL] ?: 0
        for (cp in text.codePoints().toArray()) {
            val sym = index[cp]
            if (sym == null) {
                e.bits(ESCAPE, treeBits, probs[context]); context = ESCAPE
                e.literal(cp)
            } else {
                e.bits(sym, treeBits, probs[context]); context = sym
            }
        }
        e.bits(terminator(), treeBits, probs[context])
        return e.finish()
    }

    /** Inverse of [encode]. Returns null if the payload is not decodable text. */
    fun decode(data: ByteArray): String? {
        val d = Decoder(data)
        val sb = StringBuilder()
        var context = index[CTRL] ?: 0
        while (true) {
            val sym = d.bits(treeBits, probs[context]) ?: return null
            if (sym == terminator()) break
            if (sym == ESCAPE) {
                val cp = d.literal() ?: return null
                sb.appendCodePoint(cp); context = ESCAPE
            } else {
                if (sym >= alphabetSize) return null
                sb.appendCodePoint(alpha[sym]); context = sym
            }
        }
        return sb.toString().ifEmpty { null }
    }

    /** '$' sentinel index, used as the end-of-message symbol. */
    private fun terminator(): Int = index[0x0024] ?: (alphabetSize - 1)

    /** LZMA-style range coder: 32-bit range, 64-bit low with deferred carry. */
    private class Encoder {
        private var out = ByteArray(256)
        private var pos = 0
        private var low = 0L
        private var range = -1                      // 0xFFFFFFFF as a signed Int
        private var cache = 0
        private var cacheSize = 1

        private fun put(b: Int) {
            if (pos == out.size) out = out.copyOf(out.size * 2)
            out[pos++] = b.toByte()
        }

        private fun shiftLow() {
            if (low < 0xFF000000L || low > 0xFFFFFFFFL) {
                val carry = (low ushr 32).toInt()
                repeat(cacheSize) { put((cache + carry) and 0xFF) }
                cacheSize = 0
                cache = ((low ushr 24) and 0xFF).toInt()
            }
            cacheSize++
            low = (low and 0xFFFFFF) shl 8
            range = range shl 8
        }

        /** Emits one binary decision under p = P(bit 0) scaled to 2048. */
        fun bit(b: Int, p: Int) {
            val bound = (range ushr 11) * p
            if (b == 0) range = bound
            else { low += bound; range -= bound }
            while (range and TOP == 0) { range = range shl 8; shiftLow() }
        }

        fun bits(value: Int, n: Int, tree: IntArray) {
            var node = 1
            for (i in n - 1 downTo 0) {
                val b = (value ushr i) and 1
                bit(b, tree[node])
                node = (node shl 1) or b
            }
        }

        /** Escape payload: a 21-bit code point, plain binary, no model. */
        fun literal(cp: Int) { for (i in 20 downTo 0) bit((cp ushr i) and 1, 1024) }

        fun finish(): ByteArray {
            repeat(5) { shiftLow() }
            return out.copyOf(pos)
        }
    }

    private class Decoder(private val src: ByteArray) {
        private var pos = 0
        private var low = 0L
        private var range = -1
        private var code = 0

        init {
            // The encoder's first byte is the cache flush; read 5 bytes to fill the window.
            repeat(5) { code = (code shl 8) or next() }
            range = -1
        }

        private fun next(): Int = if (pos < src.size) src[pos++].toInt() and 0xFF else 0

        fun bit(p: Int): Int {
            val bound = (range ushr 11) * p
            return if (code.toLong() and 0xFFFFFFFFL < low + bound.toLong()) {
                range = bound; 0
            } else {
                low += bound; range -= bound; 1
            }.also {
                while (range and TOP == 0) { range = range shl 8; code = ((code shl 8) or next()) and 0xFFFFFFFF.toInt() }
            }
        }

        fun bits(n: Int, tree: IntArray): Int? {
            var node = 1
            var v = 0
            for (i in 0 until n) {
                val b = bit(tree[node])
                v = (v shl 1) or b
                node = (node shl 1) or b
                if (node >= tree.size) return if (i == n - 1) v else null
            }
            return v
        }

        fun literal(): Int? {
            var cp = 0
            for (i in 0 until 21) { cp = (cp shl 1) or bit(1024) }
            return if (cp in 0..0x10FFFF) cp else null
        }
    }
}
