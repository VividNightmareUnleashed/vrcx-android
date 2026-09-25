package io.github.vrcxandroid.logwatcher

/**
 * Text behaviour of the .NET runtime that upstream `Dotnet/LogWatcher.cs` relies on, reproduced exactly where the
 * output depends on it. Each function is checked against the
 * output of the real .NET 10 runtime (test resources logwatcher/probes/).
 */
internal object DotNetUtf8 {
    private const val REPLACEMENT = 0xFFFD.toChar()

    /**
     * Decodes UTF-8 like .NET's `UTF8Encoding` in replacement mode: every maximal subpart of an ill-formed sequence
     * becomes one U+FFFD (Unicode §3.9 / WHATWG), and an incomplete sequence at the end becomes one U+FFFD, which is
     * what `StreamReader` produces when it flushes its decoder at EOF. The JDK decoder differs on surrogates
     * (`ED A0 80` gives one U+FFFD instead of three).
     */
    fun decode(b: ByteArray, from: Int, to: Int): String {
        var i = from
        while (i < to && b[i] >= 0) i++
        if (i == to) return String(b, from, to - from, Charsets.ISO_8859_1)
        val sb = StringBuilder(to - from)
        for (k in from until i) sb.append(b[k].toInt().toChar())
        var needed = 0
        var seen = 0
        var cp = 0
        var lower = 0x80
        var upper = 0xBF
        var p = i
        while (p < to) {
            val x = b[p].toInt() and 0xFF
            if (needed == 0) {
                when (x) {
                    in 0x00..0x7F -> sb.append(x.toChar())
                    in 0xC2..0xDF -> {
                        needed = 1
                        cp = x and 0x1F
                    }
                    in 0xE0..0xEF -> {
                        if (x == 0xE0) lower = 0xA0
                        if (x == 0xED) upper = 0x9F
                        needed = 2
                        cp = x and 0x0F
                    }
                    in 0xF0..0xF4 -> {
                        if (x == 0xF0) lower = 0x90
                        if (x == 0xF4) upper = 0x8F
                        needed = 3
                        cp = x and 0x07
                    }
                    else -> sb.append(REPLACEMENT)
                }
                p++
                continue
            }
            if (x < lower || x > upper) {
                // The bytes so far are a maximal subpart: one U+FFFD, then reprocess this byte as a new start.
                cp = 0
                needed = 0
                seen = 0
                lower = 0x80
                upper = 0xBF
                sb.append(REPLACEMENT)
                continue
            }
            lower = 0x80
            upper = 0xBF
            cp = (cp shl 6) or (x and 0x3F)
            seen++
            p++
            if (seen == needed) {
                sb.appendCodePoint(cp)
                cp = 0
                needed = 0
                seen = 0
            }
        }
        if (needed != 0) sb.append(REPLACEMENT)
        return sb.toString()
    }
}

/**
 * `System.Text.Json.JsonSerializer.Serialize(string[])` with the default options (`JavaScriptEncoder.Default`), which
 * is how upstream builds the `GetLogLines()` strings (`LogWatcher.cs:292`). Rules, as observed on .NET 10:
 * backslash as `\\`; backspace, tab, LF, form feed and CR as the short escapes `\b \t \n \f \r`; the double quote,
 * `& ' + < >`, backquote, the other C0 controls, DEL and every non-ASCII UTF-16 code unit as a six-character
 * `\uXXXX` escape (uppercase hex); a surrogate that is not part of a high-low pair as `�`; `null` elements as
 * `null`. Upstream's fixed index arithmetic can split a pair (`Remove(Length - 1)` after a non-BMP character, the
 * skips after `OnPlayerJoined`/`OnPlayerLeft`), so lone surrogates do reach this code.
 */
internal object DotNetJson {
    private const val HEX = "0123456789ABCDEF"
    private const val REPLACEMENT = 0xFFFD

    fun serialize(record: Array<String?>): String {
        val sb = StringBuilder(96)
        sb.append('[')
        for (i in record.indices) {
            if (i > 0) sb.append(',')
            val s = record[i]
            if (s == null) sb.append("null") else appendString(sb, s)
        }
        sb.append(']')
        return sb.toString()
    }

    fun appendString(sb: StringBuilder, s: String) {
        sb.append('"')
        var paired = false
        for (i in s.indices) {
            val c = s[i]
            var code = c.code
            if (paired) {
                // the low half of a pair whose high half was just written
                paired = false
            } else if (Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) {
                paired = true
            } else if (Character.isSurrogate(c)) {
                code = REPLACEMENT
            }
            when {
                code == 0x0A -> sb.append("\\n")
                code == 0x0D -> sb.append("\\r")
                code == 0x09 -> sb.append("\\t")
                code == 0x08 -> sb.append("\\b")
                code == 0x0C -> sb.append("\\f")
                code == 0x5C -> sb.append("\\\\")
                code < 0x20 || code >= 0x7F || code == 0x22 || code == 0x26 || code == 0x27 || code == 0x2B ||
                    code == 0x3C || code == 0x3E || code == 0x60 -> {
                    sb.append("\\u")
                    sb.append(HEX[(code shr 12) and 0xF])
                    sb.append(HEX[(code shr 8) and 0xF])
                    sb.append(HEX[(code shr 4) and 0xF])
                    sb.append(HEX[code and 0xF])
                }
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }
}

internal object DotNetStrings {
    /** `Regex("[^a-zA-Z0-9_\\-~:()]").Replace(s, "")` on UTF-16 code units (`LogWatcher.cs:36`). */
    fun cleanId(s: String): String {
        var keepAll = true
        for (c in s) if (!idChar(c)) {
            keepAll = false
            break
        }
        if (keepAll) return s
        val sb = StringBuilder(s.length)
        for (c in s) if (idChar(c)) sb.append(c)
        return sb.toString()
    }

    private fun idChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-' || c == '~' || c == ':' || c == '(' || c == ')'

    /** `Regex("[/]").Replace(s, "")`. */
    fun cleanLocation(s: String): String = if (s.indexOf('/') < 0) s else s.replace("/", "")

    /**
     * `s.StartsWith(prefix)` with the current culture (ICU collation), used by upstream for the VRCX-local URL filter
     * (A19/A20). For an ASCII [prefix] this reduces to: completely ignorable code points in [s] are skipped, the other
     * characters must match exactly, and the match fails when the next character would combine with the last
     * matched one. The two code point sets were measured with .NET 10 (en-US) over every code point
     * (probe `startswith`, logwatcher/probes/startswith.txt).
     */
    fun cultureStartsWith(s: String, prefix: String): Boolean {
        var i = 0
        for (j in prefix.indices) {
            while (i < s.length) {
                val cp = s.codePointAt(i)
                if (inRanges(IGNORABLE, cp)) i += Character.charCount(cp) else break
            }
            if (i >= s.length || s[i] != prefix[j]) return false
            i++
        }
        if (i < s.length && inRanges(COMBINING, s.codePointAt(i))) return false
        return true
    }

    private fun inRanges(r: IntArray, cp: Int): Boolean {
        var lo = 0
        var hi = r.size / 2 - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            when {
                cp < r[mid * 2] -> hi = mid - 1
                cp > r[mid * 2 + 1] -> lo = mid + 1
                else -> return true
            }
        }
        return false
    }

    private fun ranges(spec: String): IntArray {
        val out = ArrayList<Int>()
        for (part in spec.split(',')) {
            val dash = part.indexOf('-')
            if (dash < 0) {
                val v = part.toInt(16)
                out += v
                out += v
            } else {
                out += part.substring(0, dash).toInt(16)
                out += part.substring(dash + 1).toInt(16)
            }
        }
        return out.toIntArray()
    }

    /** Code points ICU ignores completely in a culture-sensitive comparison. */
    internal val IGNORABLE: IntArray = ranges(
        "0-8,E-1F,7F-84,86-9F,AD,34F,488-489,591-5AF,5BD,5C4-5C5,600-605,610-61A,61C,640,6D6-6DD,6DF-6E4,6E7-6E8," +
            "6EA-6ED,70F,740,743-744,747-74A,7FA,890-891,898-89D,8CA-8E2,8EA-8EF,8F3,951-952,F18-F19,F35,F37,F3E-F3F," +
            "F86-F87,FC6,17B4-17B5,17D3,180A-180F,1A7F,1B6B-1B73,1CD0-1CE8,1CF4,1CF7-1CF9,200B-200F,202A-202E," +
            "2060-2064,2066-206F,2D7F,A670-A672,A8E0-A8F1,FE00-FE0F,FE21,FE23-FE26,FE28,FE2A-FE2D,FE2F,FE73,FEFF," +
            "FFF9-FFFB,102E0,10EFD-10EFF,110BD,110CD,11366-1136C,11370-11374,13430-13440,13447-13455,16FE4," +
            "1BCA0-1BCA3,1CF00-1CF2D,1CF30-1CF46,1D165-1D169,1D16D-1D182,1D185-1D18B,1D1AA-1D1AD,1D242-1D244," +
            "1DA00-1DA36,1DA3B-1DA6C,1DA75,1DA84,1DA9B-1DA9F,1DAA1-1DAAF,1E8D0-1E8D6,E0001,E0020-E007F,E0100-E01EF",
    )

    /** Code points that, directly after the prefix, make ICU's prefix match fail (they combine with it). */
    internal val COMBINING: IntArray = ranges(
        "300-34E,350-362,483-487,5B0-5BC,5BF,5C1-5C2,5C7,64B-65F,670,711,730-73F,741-742,745-746,7EB-7F3,7FD," +
            "818-819,81C-82D,859-85B,89E-89F,8E3-8E9,8F0-8F2,8F4-903,93C,953-954,981-983,9BC,9FE,A01-A03,A3C,A70-A71," +
            "A81-A83,ABC,AFA-AFF,B01-B03,B3C,B55,B82,C00-C04,C3C,C81-C83,CBC,CF3,D00-D03,D81-D83,E47-E4E,EC8-ECE,F39," +
            "F7E-F7F,F82-F83,1036-1038,135D-135F,17C6-17D1,17DD,1939-193B,1A74-1A7C,1AB0-1ABE,1AC1-1ACB,1B00-1B04," +
            "1B34,1B80-1B82,1BE6,1C37,1CED,1CF2-1CF3,1DC0-1DC9,1DCB-1DD1,1DF5-1DFF,20D0-20F0,2CEF-2CF1,302A-302F," +
            "3099-309A,A66F,A67C-A67D,A6F0-A6F1,A80B,A880-A881,A8C5,A92B-A92D,A980-A983,A9B3,AABF,AAC1,ABEC,FB1E," +
            "FC5E-FC63,FCF2-FCF4,FE20,FE22,FE27,FE29,FE2E,FE70-FE72,FE74,FE76-FE7F,FF9E-FF9F,101FD,10A0D-10A0F," +
            "10A38-10A3A,10AE5-10AE6,10D24-10D27,10EAB-10EAC,10F46-10F50,10F82-10F85,11000-11002,11080-11082,110BA," +
            "11100-11102,11173,11180-11182,111C9-111CC,111CF,11234,11236-11237,1123E,112DF,112E9,11300-11303," +
            "1133B-1133C,11443-11446,1145E,114BF-114C1,114C3,115BC-115BE,115C0,1163D-1163E,11640,116AB-116AC,116B7," +
            "11837-11838,1183A,1193B-1193C,11943,119DE-119DF,11A33,11A35-11A39,11A96-11A98,11C3C-11C3E,11CB5-11CB6," +
            "11D40-11D43,11D95-11D96,11F00-11F01,11F03,16AF0-16AF4,16B30-16B36,16FF0-16FF1,1BC9D-1BC9E,1E130-1E136," +
            "1E2AE,1E2EC-1E2EF,1E4EC-1E4EF,1E944-1E94A",
    )
}
