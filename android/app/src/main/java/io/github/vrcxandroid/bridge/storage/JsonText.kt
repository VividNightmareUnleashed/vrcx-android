package io.github.vrcxandroid.bridge.storage

/**
 * JSON string writers matching the three serializers upstream uses.
 * All three produce text that `JSON.parse` reads identically; they differ only in which characters are escaped.
 */
internal object JsonText {
    private val HEX_LOWER = "0123456789abcdef".toCharArray()
    private val HEX_UPPER = "0123456789ABCDEF".toCharArray()

    /** Minimal escaping, like `JSON.stringify`: quote, backslash and control characters. */
    fun appendQuoted(sb: StringBuilder, s: String) {
        sb.append('"')
        var start = 0
        for (i in s.indices) {
            val c = s[i]
            if (c >= ' ' && c != '"' && c != '\\') continue
            sb.append(s, start, i)
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> appendUnicodeEscape(sb, c, HEX_LOWER)
            }
            start = i + 1
        }
        sb.append(s, start, s.length)
        sb.append('"')
    }

    fun quoted(s: String): String = StringBuilder(s.length + 2).also { appendQuoted(it, s) }.toString()

    /**
     * System.Text.Json with the default `JavaScriptEncoder`: everything outside printable ASCII, and the HTML-sensitive
     * characters `" & ' + < > \``, is written as an upper-case `\uXXXX` escape (surrogates one by one).
     */
    fun appendQuotedSystemTextJson(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '"', '&', '\'', '+', '<', '>', '`' -> appendUnicodeEscape(sb, c, HEX_UPPER)
                else -> if (c < ' ' || c > '~') appendUnicodeEscape(sb, c, HEX_UPPER) else sb.append(c)
            }
        }
        sb.append('"')
    }

    /** Newtonsoft.Json default escaping (`StringEscapeHandling.Default`): control characters, quote, backslash, U+0085/2028/2029. */
    fun appendQuotedNewtonsoft(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\u0085', '\u2028', '\u2029' -> appendUnicodeEscape(sb, c, HEX_LOWER)
                else -> if (c < ' ') appendUnicodeEscape(sb, c, HEX_LOWER) else sb.append(c)
            }
        }
        sb.append('"')
    }

    /** `System.Text.Json.JsonSerializer.Serialize(Dictionary<string,string>)`: compact, default encoder. */
    fun systemTextJsonObject(map: Map<String, String?>): String {
        val sb = StringBuilder(16 + map.size * 32)
        sb.append('{')
        var first = true
        for ((k, v) in map) {
            if (!first) sb.append(',')
            first = false
            appendQuotedSystemTextJson(sb, k)
            sb.append(':')
            if (v == null) sb.append("null") else appendQuotedSystemTextJson(sb, v)
        }
        sb.append('}')
        return sb.toString()
    }

    private fun appendUnicodeEscape(sb: StringBuilder, c: Char, hex: CharArray) {
        val v = c.code
        sb.append('\\').append('u')
            .append(hex[(v shr 12) and 0xF])
            .append(hex[(v shr 8) and 0xF])
            .append(hex[(v shr 4) and 0xF])
            .append(hex[v and 0xF])
    }
}
