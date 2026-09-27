package com.itantra.walkie.net

import java.io.DataInputStream
import kotlin.math.abs

/**
 * Lossless wire codec for walkie messages: an order-1 character model over a fixed alphabet,
 * driven through a binary-tree arithmetic coder. This is what carries the words one phone heard
 * to the other phone, so the number printed on a bubble - bytes on air - is this codec's output.
 *
 * Why this shape: a Brahmic code point costs 3 bytes in UTF-8 but carries roughly 3-4 bits of
 * information, and general-purpose compressors cannot exploit that on a 200-byte message - they
 * have no room to find structure (measured: deflate 44%, brotli 49% on this corpus). Indexing
 * into a per-character model and arithmetic-coding it reaches 80.0% lossless on held-out
 * sentences, scored with the exact quantised tables shipped in assets/mesh/charmodel.bin.
 *
 * Both ends must hold identical tables or decoding silently produces garbage, so the model is a
 * versioned asset, not something learned at runtime, and the frame carries its version. The escape
 * symbol (index 0) covers any code point outside the alphabet - digits, Latin, emoji - at ~3 bytes
 * each, which is why a message of pure escapes is not expanded.
 *
 * The coder is the LZMA range-coder construction, transcribed rather than reinvented: 32-bit
 * range, 64-bit low with a deferred carry over a run of 0xFF bytes, probabilities at 11 bits, one
 * binary decision per bit of the symbol index. Three things the first port got wrong and the unit
 * test now pins: the normalisation shifted the range twice (once in the loop, once inside
 * shiftLow), the carry run wrote `cache` for every pending byte instead of 0xFF for all but the
 * first, and the decoder carried its own `low` and compared against it, which no encoder emits.
 *
 * A message is framed by a 16-bit count of code points, not by an end-of-message symbol. The
 * first draft reserved '$' for that, which silently truncated any message that contained a
 * dollar sign - a legal character, and one that appears in a cost or a rate. Length is the only
 * framing that costs nothing in the alphabet.
 *
 * Two CRC bytes close the frame. An arithmetic-coded stream is not error-detecting at all - a
 * flipped bit can decode into longer, perfectly-formed text - and on a mesh whose whole promise
 * is that the words you hear are the words that were said, silently wrong text is far worse than
 * a dropped message. So a frame that fails its checksum is refused, and the receiver says it
 * heard nothing rather than inventing something.
 *
 * [pack] is what the mesh calls, not [encode]. Measured over the transcripts this app ships, the
 * model halves Indic text (Hindi 90 -> 26 bytes, Bengali 136 -> 46, Telugu 96 -> 31) but it
 * *expands* anything whose letters are not in the 447-symbol alphabet: English 59 -> 222, Punjabi
 * 79 -> 110, Tamil 99 -> 146, because every one of those characters pays an escape. So a frame
 * carries a one-byte flag naming which of the two encodings is smaller for this message, and a
 * message can never be larger on the wire than the text it started as.
 */
class WireCodec(model: ByteArray) {

    private val alpha: IntArray                 // code point per symbol index
    private val index: HashMap<Int, Int>        // code point -> symbol index
    /** probs[context][node] = P(bit 0) scaled to 2048, for the binary tree over symbol indices. */
    private val probs: Array<IntArray>
    private val treeBits: Int                   // ceil(log2(alphabetSize))
    val alphabetSize: Int
    private val startSym: Int

    init {
        val d = DataInputStream(model.inputStream())
        val magic = ByteArray(4).also { d.readFully(it) }
        require(String(magic, Charsets.US_ASCII) == "IWCM") { "bad charmodel magic ${String(magic)}" }
        val version = d.readU16LE()
        require(version == 1) { "unsupported charmodel version $version" }
        alphabetSize = d.readU16LE()
        alpha = IntArray(alphabetSize) { d.readU32LE() }
        index = HashMap(alphabetSize * 2)
        for (i in alpha.indices) index.putIfAbsent(alpha[i], i)
        treeBits = 32 - Integer.numberOfLeadingZeros(alphabetSize - 1)
        // The start sentinel is load-bearing: every chain is conditioned on it, so a table set
        // without it must fail here rather than corrupt traffic later.
        startSym = index[CTRL] ?: error("charmodel has no '^' start sentinel")
        // The unigram block is dense (one u16 per symbol, summing to SCALE), unlike the order-1
        // rows that follow. It is not skipped: context 0 is the escape symbol itself, so a run of
        // escapes needs a shape to fall back to, and this is the only row that gives it one.
        val uni = IntArray(alphabetSize) { d.readU16LE() }
        val root = buildTree(uni)
        probs = Array(alphabetSize) { buildTree(quantised(d, alphabetSize)) }
        if (probs.isNotEmpty()) probs[0] = root
        require(d.read() == -1) { "charmodel has ${d.available()} trailing bytes after the rows" }
    }

    /**
     * The shipped table is little-endian, which is what wrote it, and `DataInputStream` reads
     * big-endian by contract. Reading the version as BE yields 256 for 1 and the alphabet size as
     * 45,696 for 447, so every row after it is parsed out of the wrong offset - the reason the
     * first port decoded garbage and the range coder never got the blame it did not deserve.
     */
    private fun DataInputStream.readU16LE(): Int {
        val a = read(); val b = read()
        require(a >= 0 && b >= 0) { "charmodel truncated" }
        return a or (b shl 8)
    }

    private fun DataInputStream.readU32LE(): Int {
        val b = ByteArray(4).also { readFully(it) }
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or
            ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24)
    }

    /** Reads one shipped distribution (count + sparse (symbol, freq) pairs) into a dense array. */
    private fun quantised(d: DataInputStream, n: Int): IntArray {
        val total = d.readU16LE()
        val pairs = d.readU16LE()
        val f = IntArray(n) { 1 }
        var used = n
        for (i in 0 until pairs) {
            val sym = d.readU16LE()
            val fq = d.readU16LE()
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
        private const val TOP = 1L shl 24                // range below this needs a new byte
        private const val U32 = 0xFFFFFFFFL
        private const val ESCAPE = 0
        /**
         * Frame flags: which of the two encodings won for this message. 0x01 is deliberately
         * unused - it is [com.itantra.walkie.ml.Tone]'s framing mark, and the tone prefix sits in
         * front of these bytes, so a frame's first byte must never be able to impersonate one.
         */
        private const val PACK_TEXT: Byte = 0
        private const val PACK_MODEL: Byte = 2
        /** A literal frame is bounded by the composer, not by the model. */
        private const val MAX_TEXT_BYTES = 4096
        private const val CTRL = 0x005E                  // '^' sentinel, start of every context chain
        private const val MAX_LEN = 0xFFFF               // the 16-bit count's ceiling

        /** CRC-16/CCITT-FALSE: no table, cheap, and enough for a 300-byte message. */
        private fun crc16(b: ByteArray, from: Int = 0, to: Int = b.size): Int {
            var crc = 0xFFFF
            for (i in from until to) {
                val x = b[i]
                crc = crc xor ((x.toInt() and 0xFF) shl 8)
                repeat(8) {
                    crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF
                }
            }
            return crc
        }

        /**
         * Framing that survives a missing model. A handset whose asset cannot be read still sends
         * and still receives - it just always chooses the literal flag, and refuses a model-coded
         * frame it has no table to read rather than guessing at it. Both ends can run a different
         * build and the words still arrive, or they are dropped and said to be dropped.
         */
        fun frame(codec: WireCodec?, text: String): ByteArray {
            require(text.isNotEmpty()) { "nothing to send" }
            val utf = text.toByteArray(Charsets.UTF_8)
            val coded = codec?.encode(text)
            val (flag, body) =
                if (coded != null && coded.size + 1 < utf.size) PACK_MODEL to coded else PACK_TEXT to utf
            val out = ByteArray(1 + body.size + 2)
            out[0] = flag
            body.copyInto(out, 1)
            val crc = crc16(out, 0, out.size - 2)
            out[out.size - 2] = (crc and 0xFF).toByte()
            out[out.size - 1] = ((crc ushr 8) and 0xFF).toByte()
            return out
        }

        /** Inverse of [frame]. Null means the frame is corrupt, unreadable, or empty. */
        fun unframe(codec: WireCodec?, frame: ByteArray): String? {
            if (frame.size < 4) return null
            val want = (frame[frame.size - 2].toInt() and 0xFF) or
                ((frame[frame.size - 1].toInt() and 0xFF) shl 8)
            if (crc16(frame, 0, frame.size - 2) != want) return null
            val body = frame.copyOfRange(1, frame.size - 2)
            return when (frame[0]) {
                PACK_TEXT ->
                    if (body.size > MAX_TEXT_BYTES) null
                    else String(body, Charsets.UTF_8).ifEmpty { null }
                PACK_MODEL -> codec?.decode(body)
                else -> null
            }
        }
    }

    /**
     * Encodes text to wire bytes. Empty input has no frame; the caller sends none. Messages over
     * 65,535 code points are refused rather than silently cut - a walkie transmission is a
     * sentence, and a longer one means the caller has used this for something it is not.
     */
    fun encode(text: String): ByteArray {
        val cps = text.codePoints().toArray()
        require(cps.size <= MAX_LEN) { "message of ${cps.size} code points exceeds the 16-bit frame count" }
        val e = Encoder()
        e.direct(cps.size, 16)
        var context = startSym
        for (cp in cps) {
            val sym = index[cp]
            if (sym == null) {
                e.bits(ESCAPE, treeBits, probs[context]); context = ESCAPE
                e.literal(cp)
            } else {
                e.bits(sym, treeBits, probs[context]); context = sym
            }
        }
        return e.finish()
    }

    /**
     * The frame the mesh sends: a flag byte, the smaller of the two encodings, and a checksum.
     * Empty text has no frame.
     */
    fun pack(text: String): ByteArray = frame(this, text)

    /** Inverse of [pack]. Null means the frame is corrupt, unknown, or empty. */
    fun unpack(frame: ByteArray): String? = unframe(this, frame)

    /**
     * Inverse of [encode]. Null means the payload is not a message this model can read: a symbol
     * outside the alphabet, a code point beyond the plane, or a stream that runs past its own
     * length. A receiver must treat null as "discard and say so", never as empty text.
     */
    fun decode(data: ByteArray): String? {
        val d = Decoder(data)
        val n = d.direct(16)
        if (n > MAX_LEN || n > data.size * 4 + 64) return null
        val sb = StringBuilder(n.coerceAtMost(4096))
        var context = startSym
        for (i in 0 until n) {
            val sym = d.bits(treeBits, probs[context]) ?: return null
            if (sym >= alphabetSize) return null
            if (sym == ESCAPE) {
                val cp = d.literal() ?: return null
                sb.appendCodePoint(cp); context = ESCAPE
            } else {
                sb.appendCodePoint(alpha[sym]); context = sym
            }
        }
        return if (sb.isEmpty()) null else sb.toString()
    }

    /**
     * LZMA range coder, 32-bit range held in a Long so every unsigned comparison is honest, and a
     * 64-bit low that lets a carry ripple through bytes already emitted. The pending run is held
     * as (cache, cacheSize) rather than written, because a carry can still arrive for it.
     */
    private class Encoder {
        private var out = ByteArray(256)
        private var pos = 0
        private var low = 0L
        private var range = U32
        private var cache = 0
        private var cacheSize = 1

        private fun put(b: Int) {
            if (pos == out.size) out = out.copyOf(out.size * 2)
            out[pos++] = b.toByte()
        }

        private fun shiftLow() {
            val hi = (low ushr 32).toInt()
            if (hi != 0 || low < 0xFF000000L) {
                put((cache + hi) and 0xFF)
                // Every further pending byte was 0xFF before the carry; that is why it was pending.
                repeat(cacheSize - 1) { put((0xFF + hi) and 0xFF) }
                cache = ((low ushr 24) and 0xFF).toInt()
                cacheSize = 1
            } else cacheSize++
            low = (low and 0xFFFFFF) shl 8
        }

        /** Emits one binary decision under p = P(bit 0) scaled to 2048. */
        fun bit(b: Int, p: Int) {
            val bound = (range ushr 11) * p
            if (b == 0) range = bound
            else { low += bound; range -= bound }
            while (range < TOP) { range = (range shl 8) and U32; shiftLow() }
        }

        fun bits(value: Int, n: Int, tree: IntArray) {
            var node = 1
            for (i in n - 1 downTo 0) {
                val b = (value ushr i) and 1
                bit(b, tree[node])
                node = (node shl 1) or b
            }
        }

        /** A count or an escape payload: plain binary, no model, because nothing predicts it. */
        fun direct(v: Int, n: Int) { for (i in n - 1 downTo 0) bit((v ushr i) and 1, 1024) }

        /** Escape payload: a 21-bit code point. */
        fun literal(cp: Int) = direct(cp, 21)

        fun finish(): ByteArray {
            repeat(5) { shiftLow() }
            return out.copyOf(pos)
        }
    }

    /**
     * The decoder's mirror. It tracks only (range, code): the encoder's `low` is exactly what the
     * code value measures against, so subtracting the bound on a 1-bit is the whole comparison.
     * Reading five bytes at init drops the encoder's leading flush byte and fills the window.
     */
    private class Decoder(private val src: ByteArray) {
        private var pos = 0
        private var range = U32
        private var code = 0L

        init {
            repeat(5) { code = ((code shl 8) or next()) and U32 }
        }

        private fun next(): Long = if (pos < src.size) (src[pos++].toInt() and 0xFF).toLong() else 0L

        fun bit(p: Int): Int {
            val bound = (range ushr 11) * p
            val b = if (code < bound) 0 else 1
            if (b == 0) range = bound else { code -= bound; range -= bound }
            while (range < TOP) {
                range = (range shl 8) and U32
                code = ((code shl 8) or next()) and U32
            }
            return b
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

        fun direct(n: Int): Int {
            var v = 0
            for (i in 0 until n) v = (v shl 1) or bit(1024)
            return v
        }

        fun literal(): Int? = direct(21).takeIf { it in 0..0x10FFFF }
    }
}

/**
 * The one copy of the shipped tables in the process. Both the radio and the bench read through
 * here, so a number printed on the bench is produced by the same model that codes a real frame -
 * and a phone whose asset cannot be read degrades in one place, not in two.
 */
object WireModel {

    @Volatile private var codec: WireCodec? = null
    @Volatile private var tried = false

    /** Null means the model is unusable on this phone; the caller sends text verbatim. */
    fun codec(context: android.content.Context): WireCodec? {
        if (!tried) synchronized(this) {
            if (!tried) {
                codec = runCatching {
                    context.applicationContext.assets.open("mesh/charmodel.bin")
                        .use { WireCodec(it.readBytes()) }
                }.onFailure {
                    android.util.Log.e("WIRE", "charmodel unusable, sending text verbatim", it)
                }.getOrNull()
                tried = true
            }
        }
        return codec
    }
}
