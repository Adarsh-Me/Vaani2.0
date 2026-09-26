package com.itantra.walkie.ml

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Stored-npy (C-order) reader for the bundled .npz zips (no inflate needed: entries stored). */
object Npz {
    data class Arr(val shape: IntArray, val floats: FloatArray?, val longs: LongArray?)

    fun load(npyBytes: ByteArray): Arr {
        require(npyBytes.size > 10 && npyBytes[0] == 0x93.toByte())
        val hlen = ByteBuffer.wrap(npyBytes, 8, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
        val header = npyBytes.sliceArray(10 until 10 + hlen).toString(Charsets.US_ASCII)
        val isF4 = header.contains("'f4'") || header.contains("|f4") || header.contains("<f4")
        val isI8 = header.contains("'i8'") || header.contains("<i8") || header.contains("|i8")
        val shape = Regex("""\(([\d,\s]*)\)""").find(header)!!.groupValues[1]
            .split(",").mapNotNull { it.trim().toIntOrNull() }.toIntArray()
        val data = npyBytes.sliceArray(10 + hlen until npyBytes.size)
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        return if (isI8) {
            val n = data.size / 8; val a = LongArray(n); for (i in 0 until n) a[i] = bb.long
            Arr(shape, null, a)
        } else {
            val n = data.size / 4; val a = FloatArray(n); for (i in 0 until n) a[i] = bb.float
            Arr(a.let { shape }, a, null)
        }
    }

    fun entry(b: ByteArray, name: String): ByteArray {
        var eocd = -1
        var i = b.size - 22
        while (i >= 0 && i >= b.size - 70000) {
            if (b[i] == 0x50.toByte() && b[i + 1] == 0x4b.toByte() && b[i + 2] == 0x05.toByte() && b[i + 3] == 0x06.toByte()) { eocd = i; break }
            i--
        }
        require(eocd >= 0)
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val count = bb.getShort(eocd + 10).toInt() and 0xFFFF
        var off = bb.getInt(eocd + 16)
        repeat(count) {
            val fnLen = bb.getShort(off + 28).toInt() and 0xFFFF
            val exLen = bb.getShort(off + 30).toInt() and 0xFFFF
            val fcLen = bb.getShort(off + 32).toInt() and 0xFFFF
            val comp = bb.getShort(off + 10).toInt() and 0xFFFF
            val csize = bb.getInt(off + 20)
            val nm = b.sliceArray(off + 46 until off + 46 + fnLen).toString(Charsets.UTF_8)
            val lho = bb.getInt(off + 42)
            if (nm == name) {
                require(comp == 0) { "compressed npz unsupported" }
                val lfLen = bb.getShort(lho + 26).toInt() and 0xFFFF
                val leLen = bb.getShort(lho + 28).toInt() and 0xFFFF
                val ds = lho + 30 + lfLen + leLen
                return b.sliceArray(ds until ds + csize)
            }
            off += 46 + fnLen + exLen + fcLen
        }
        throw IllegalArgumentException("npz entry missing: $name")
    }

    fun floats(npz: ByteArray, name: String): Pair<IntArray, FloatArray> {
        val a = load(entry(npz, name)); return a.shape to (a.floats ?: throw IllegalArgumentException(name))
    }

    fun longScalar(npz: ByteArray, name: String): Long {
        val a = load(entry(npz, name)); return a.longs!![0]
    }
}

/**
 * Readers for the model constants that ship as JSON instead of npy: the mel/filterbank tables
 * and windows every frontend needs, plus a flat key:value object and a string array. Written by
 * the scripts/ fetch helpers, so the shapes here are the shapes those scripts emit.
 */
object JsonAssets {
    private val NUM = Regex("""-?\d+(\.\d+)?([eE][+-]?\d+)?""")

    fun floats(s: String): FloatArray = NUM.findAll(s).map { it.value.toFloat() }.toList().toFloatArray()

    /** Nested [[...]] as rows; the depth-2 scan keeps it independent of how it was pretty-printed. */
    fun matrix(s: String): Array<DoubleArray> {
        val rows = ArrayList<DoubleArray>()
        var depth = 0; var cur: MutableList<Double>? = null
        val num = StringBuilder()
        fun flush() { val c = cur; if (num.isNotEmpty() && c != null) { c.add(num.toString().toDouble()); num.clear() } }
        for (ch in s) {
            when {
                ch == '[' -> { depth++; if (depth == 2) cur = ArrayList() }
                ch == ']' -> { flush(); if (depth == 2 && cur != null) rows.add(cur.toDoubleArray()); depth-- }
                (ch in '0'..'9') || ch == '-' || ch == '+' || ch == '.' || ch == 'e' || ch == 'E' -> num.append(ch)
                else -> flush()
            }
        }
        return rows.toTypedArray()
    }

    /** {"key": value, ...} with the values kept as text, so the caller decides the type. */
    fun flat(s: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val kv = Regex('"([^"]+)"\s*:\s*("(?:[^"\\]|\\.)*"|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?|true|false|null)'')
        for (m in kv.findAll(s)) out[m.groupValues[1]] = m.groupValues[2]
        return out
    }

    /** ["a","b",...] - escape-aware, because a tokenizer's pieces contain quotes and slashes. */
    fun stringList(s: String): List<String> {
        val out = ArrayList<String>()
        var i = s.indexOf('[')
        if (i < 0) return out
        i++
        val sb = StringBuilder()
        var inStr = false
        while (i < s.length) {
            val ch = s[i]
            if (!inStr) {
                when (ch) {
                    '"' -> inStr = true
                    ']' -> return out
                    else -> {}
                }
                i++
                continue
            }
            if (ch == '\\') {
                val n = s[i + 1]
                when (n) {
                    'u' -> { sb.append(Integer.parseInt(s.substring(i + 2, i + 6), 16).toChar()); i += 6 }
                    'n' -> { sb.append('
'); i += 2 }
                    't' -> { sb.append('	'); i += 2 }
                    'r' -> { sb.append('
'); i += 2 }
                    else -> { sb.append(n); i += 2 }
                }
                continue
            }
            if (ch == '"') { out.add(sb.toString()); sb.setLength(0); inStr = false; i++; continue }
            sb.append(ch); i++
        }
        return out
    }
}
