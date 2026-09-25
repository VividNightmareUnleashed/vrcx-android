package io.github.vrcxandroid.bridge.appapi

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Minimal JSON tree that prints exactly like Newtonsoft.Json with `Formatting.Indented` (two-space indent, `"key": value`,
 * `[]`/`{}` for empty containers, Newtonsoft's default string escaping and float formatting). Upstream returns several
 * JSON strings built this way (screenshot metadata, `GetExtraScreenshotData`, `FindScreenshotsBySearch`); line breaks
 * are `\n` as on the Electron (Linux) build.
 */
sealed class NJ {
    class Obj(val members: LinkedHashMap<String, NJ> = LinkedHashMap()) : NJ() {
        operator fun set(key: String, value: NJ) {
            members[key] = value
        }

        fun put(key: String, value: String?) = set(key, if (value == null) Null else Str(value))
    }

    class Arr(val items: MutableList<NJ> = mutableListOf()) : NJ()
    class Str(val value: String) : NJ()
    class Int(val value: Long) : NJ()
    class Flt(val value: Float) : NJ()
    object Null : NJ()

    fun toIndentedString(): String = StringBuilder().also { write(it, 0) }.toString()

    private fun write(sb: StringBuilder, depth: kotlin.Int) {
        when (this) {
            is Obj -> {
                if (members.isEmpty()) {
                    sb.append("{}")
                    return
                }
                sb.append('{')
                var first = true
                for ((key, value) in members) {
                    if (!first) sb.append(',')
                    first = false
                    sb.append('\n')
                    indent(sb, depth + 1)
                    appendString(sb, key)
                    sb.append(": ")
                    value.write(sb, depth + 1)
                }
                sb.append('\n')
                indent(sb, depth)
                sb.append('}')
            }
            is Arr -> {
                if (items.isEmpty()) {
                    sb.append("[]")
                    return
                }
                sb.append('[')
                items.forEachIndexed { i, item ->
                    if (i > 0) sb.append(',')
                    sb.append('\n')
                    indent(sb, depth + 1)
                    item.write(sb, depth + 1)
                }
                sb.append('\n')
                indent(sb, depth)
                sb.append(']')
            }
            is Str -> appendString(sb, value)
            is Int -> sb.append(value)
            is Flt -> sb.append(formatFloat(value))
            Null -> sb.append("null")
        }
    }

    companion object {
        private fun indent(sb: StringBuilder, depth: kotlin.Int) {
            repeat(depth * 2) { sb.append(' ') }
        }

        /** Newtonsoft's default escaping: `"`, `\`, control characters, U+0085, U+2028 and U+2029. */
        fun appendString(sb: StringBuilder, s: String) {
            sb.append('"')
            for (c in s) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    '\b' -> sb.append("\\b")
                    '\u000c' -> sb.append("\\f")
                    else -> if (c < ' ' || c.code == 0x85 || c.code == 0x2028 || c.code == 0x2029) {
                        sb.append("\\u")
                        val hex = Integer.toHexString(c.code)
                        repeat(4 - hex.length) { sb.append('0') }
                        sb.append(hex)
                    } else {
                        sb.append(c)
                    }
                }
            }
            sb.append('"')
        }

        /**
         * `JsonConvert.ToString(float)`: the shortest round-trip digits ("R"), in fixed notation when the decimal
         * exponent is in -5 < e < 9 and as `d.dddE+xx` otherwise, with ".0" added to integral fixed values.
         */
        fun formatFloat(f: Float): String {
            if (f.isNaN()) return "NaN"
            if (f.isInfinite()) return if (f > 0) "Infinity" else "-Infinity"
            if (f == 0f) return if (1f / f < 0) "-0.0" else "0.0"
            val shortest = shortestDecimal(f)
            val negative = shortest.signum() < 0
            val unscaled = shortest.unscaledValue().abs().toString().trimEnd('0').ifEmpty { "0" }
            // exponent of the first significant digit
            val exponent = shortest.precision() - shortest.scale() - 1
            val body = if (exponent > -5 && exponent < 9) {
                val plain = shortest.abs().stripTrailingZeros().toPlainString()
                if (plain.contains('.')) plain else "$plain.0"
            } else {
                val mantissa = if (unscaled.length > 1) unscaled[0] + "." + unscaled.substring(1) else unscaled
                val expDigits = kotlin.math.abs(exponent).toString().padStart(2, '0')
                mantissa + "E" + (if (exponent < 0) "-" else "+") + expDigits
            }
            return if (negative) "-$body" else body
        }

        /** Fewest significant digits (1..9) that parse back to exactly [f]. */
        private fun shortestDecimal(f: Float): BigDecimal {
            val exact = BigDecimal(f.toDouble())
            for (digits in 1..9) {
                val candidate = exact.round(MathContext(digits, RoundingMode.HALF_EVEN))
                if (candidate.toFloat() == f) return candidate.stripTrailingZeros()
            }
            return exact.round(MathContext(9, RoundingMode.HALF_EVEN)).stripTrailingZeros()
        }

        /**
         * `(bytes / 1024f / 1024f).ToString("0.00")`: .NET custom formats round a float to 7 significant digits first,
         * then round half away from zero at the second decimal.
         */
        fun formatMegabytes(bytes: Long): String {
            val mb = bytes.toFloat() / 1024f / 1024f
            if (mb == 0f) return "0.00"
            val digits7 = BigDecimal(mb.toDouble()).round(MathContext(7, RoundingMode.HALF_EVEN))
            return digits7.setScale(2, RoundingMode.HALF_UP).toPlainString()
        }
    }
}
