package ir.mafia

object Json {
    fun q(s: String): String {
        val b = StringBuilder("\"")
        for (c in s) when (c) {
            '"' -> b.append("\\\"")
            '\\' -> b.append("\\\\")
            '\n' -> b.append("\\n")
            '\r' -> b.append("\\r")
            '\t' -> b.append("\\t")
            else -> if (c < ' ') b.append(String.format("\\u%04x", c.code)) else b.append(c)
        }
        return b.append('"').toString()
    }

    fun write(v: Any?): String = when (v) {
        null -> "null"
        is String -> q(v)
        is Boolean, is Number -> v.toString()
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") { q(it.key.toString()) + ":" + write(it.value) }
        is Iterable<*> -> v.joinToString(",", "[", "]") { write(it) }
        else -> q(v.toString())
    }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun str(): String {
            i++
            val b = StringBuilder()
            while (s[i] != '"') {
                var c = s[i++]
                if (c == '\\') {
                    c = s[i++]
                    when (c) {
                        'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r')
                        'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> b.append(c)
                    }
                } else b.append(c)
            }
            i++
            return b.toString()
        }
        fun v(): Any? {
            ws()
            when (s[i]) {
                '{' -> {
                    i++
                    val m = LinkedHashMap<String, Any?>()
                    ws()
                    if (s[i] == '}') { i++; return m }
                    while (true) {
                        ws(); val k = str(); ws(); i++
                        m[k] = v(); ws()
                        if (s[i] == ',') i++ else { i++; break }
                    }
                    return m
                }
                '[' -> {
                    i++
                    val l = ArrayList<Any?>()
                    ws()
                    if (s[i] == ']') { i++; return l }
                    while (true) {
                        l.add(v()); ws()
                        if (s[i] == ',') i++ else { i++; break }
                    }
                    return l
                }
                '"' -> return str()
                else -> {
                    val st = i
                    while (i < s.length && s[i] != ',' && s[i] != '}' && s[i] != ']' && !s[i].isWhitespace()) i++
                    return when (val t = s.substring(st, i)) {
                        "true" -> true
                        "false" -> false
                        "null" -> null
                        else -> t.toDouble()
                    }
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun parseMap(s: String): Map<String, Any?> = try {
        (Parser(s).v() as? Map<String, Any?>) ?: emptyMap()
    } catch (e: Exception) { emptyMap() }
}
