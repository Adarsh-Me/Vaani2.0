package com.itantra.walkie.ml

/**
 * Readers for the model constants that ship as JSON rather than npy: the mel and filterbank
 * tables every frontend needs, the flat object of scalar parameters, and a tokenizer's piece
 * list. Written by the scripts/ fetch helpers, so the shapes accepted here are the shapes those
 * scripts emit - and both are scanned rather than parsed, because a 150 KB table is not worth a
 * JSON dependency in an app whose whole job is to avoid one.
 */
object JsonAssets {
    private val NUM = Regex("""-?\d+(\.\d+)?([eE][+-]?\d+)?""")

    /** Every number in the text, in order; a flat array is just this. */
    fun floats(s: String): FloatArray = NUM.findAll(s).map { it.value.toFloat() }.toList().toFloatArray()

    /** Nested [[...]] as rows. The depth-2 scan does not care how the file was pretty-printed. */
    fun matrix(s: String): Array<DoubleArray> {
        val rows = ArrayList<DoubleArray>()
        var depth = 0
        var cur: MutableList<Double>? = null
        val num = StringBuilder()
        fun flush() {
            val c = cur
            if (num.isNotEmpty() && c != null) { c.add(num.toString().toDouble()); num.setLength(0) }
        }
        for (ch in s) {
            when {
                ch == '[' -> { depth++; if (depth == 2) cur = ArrayList() }
                ch == ']' -> { flush(); if (depth == 2 && cur != null) rows.add(cur.toDoubleArray()); depth-- }
                ch in '0'..'9' || ch == '-' || ch == '+' || ch == '.' || ch == 'e' || ch == 'E' -> num.append(ch)
                else -> flush()
            }
        }
        return rows.toTypedArray()
    }

    /** `{"key": value, ...}` with the values kept as text, so the caller decides the type. */
    fun flat(s: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val kv = Regex(""""([^"]+)"\s*:\s*("(?:[^"\\]|\\.)*"|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?|true|false|null)""")
        for (m in kv.findAll(s)) out[m.groupValues[1]] = m.groupValues[2]
        return out
    }

    /** `["a","b",...]`, escape-aware: a tokenizer's pieces contain quotes and backslashes. */
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
                    'n' -> { sb.append('\n'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    else -> { sb.append(n); i += 2 }
                }
                continue
            }
            if (ch == '"') {
                out.add(sb.toString()); sb.setLength(0); inStr = false; i++; continue
            }
            sb.append(ch); i++
        }
        return out
    }
}
