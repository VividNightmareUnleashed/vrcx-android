package io.github.vrcxandroid.bridge.appapi

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * A .NET `DateTime` as far as the screenshot metadata needs it: a wall-clock value with 100 ns precision and a kind.
 * [format] prints it like Newtonsoft.Json's default ISO format (`yyyy-MM-ddTHH:mm:ss.FFFFFFFK`).
 */
class NetDateTime(val wallClock: LocalDateTime, val kind: Kind, private val localOffset: ZoneOffset? = null) {
    enum class Kind { UNSPECIFIED, UTC, LOCAL }

    fun format(): String {
        val sb = StringBuilder()
        sb.append(pad(wallClock.year, 4)).append('-').append(pad(wallClock.monthValue, 2)).append('-')
            .append(pad(wallClock.dayOfMonth, 2)).append('T').append(pad(wallClock.hour, 2)).append(':')
            .append(pad(wallClock.minute, 2)).append(':').append(pad(wallClock.second, 2))
        val ticks = wallClock.nano / 100
        if (ticks != 0) sb.append('.').append(pad(ticks, 7).trimEnd('0'))
        when (kind) {
            Kind.UTC -> sb.append('Z')
            Kind.LOCAL -> {
                val total = localOffset?.totalSeconds ?: 0
                val abs = kotlin.math.abs(total)
                sb.append(if (total < 0) '-' else '+').append(pad(abs / 3600, 2)).append(':').append(pad(abs / 60 % 60, 2))
            }
            Kind.UNSPECIFIED -> {}
        }
        return sb.toString()
    }

    override fun toString(): String = format()

    companion object {
        private val PATTERN = Regex(
            "^\\s*(\\d{4})-(\\d{1,2})-(\\d{1,2})" +
                "(?:[T ](\\d{1,2}):(\\d{2})(?::(\\d{2})(?:[.,](\\d+))?)?)?" +
                "\\s*(Z|z|[+-]\\d{2}(?::?\\d{2})?)?\\s*$",
        )

        private fun pad(value: Int, width: Int): String = value.toString().padStart(width, '0')

        private class Parsed(val wallClock: LocalDateTime, val offset: ZoneOffset?, val isUtcDesignator: Boolean)

        private fun parse(text: String?): Parsed? {
            if (text == null) return null
            val m = PATTERN.matchEntire(text) ?: return null
            val g = m.groupValues
            return try {
                val year = g[1].toInt()
                val month = g[2].toInt()
                val day = g[3].toInt()
                val hour = g[4].takeIf { it.isNotEmpty() }?.toInt() ?: 0
                val minute = g[5].takeIf { it.isNotEmpty() }?.toInt() ?: 0
                val second = g[6].takeIf { it.isNotEmpty() }?.toInt() ?: 0
                var ticks = 0L
                if (g[7].isNotEmpty()) {
                    ticks = BigDecimal("0." + g[7]).movePointRight(7).setScale(0, RoundingMode.HALF_EVEN).toLong()
                }
                var wall = LocalDateTime.of(year, month, day, hour, minute, second)
                wall = wall.plusNanos(ticks * 100)
                val zoneText = g[8]
                val offset = when {
                    zoneText.isEmpty() -> null
                    zoneText == "Z" || zoneText == "z" -> ZoneOffset.UTC
                    else -> {
                        val sign = if (zoneText[0] == '-') -1 else 1
                        val digits = zoneText.substring(1).replace(":", "")
                        val h = digits.substring(0, 2).toInt()
                        val mm = if (digits.length >= 4) digits.substring(2, 4).toInt() else 0
                        if (h > 14 || mm > 59) return null
                        ZoneOffset.ofTotalSeconds(sign * (h * 3600 + mm * 60))
                    }
                }
                Parsed(wall, offset, zoneText == "Z" || zoneText == "z")
            } catch (e: RuntimeException) {
                null
            }
        }

        /** Converts to [zone] keeping the offset in effect at that instant (like .NET's ambiguous-DST flag). */
        private fun toLocal(p: Parsed, zone: ZoneId): NetDateTime {
            val zoned = p.wallClock.atOffset(p.offset!!).atZoneSameInstant(zone)
            return NetDateTime(zoned.toLocalDateTime(), Kind.LOCAL, zoned.offset)
        }

        /**
         * `DateTime.TryParse` for the ISO-like strings VRChat writes into XMP: a zone designator makes the value local
         * (converted to [zone]); without one the value is unspecified. Returns null when it does not parse.
         */
        fun tryParse(text: String?, zone: ZoneId = ZoneId.systemDefault()): NetDateTime? {
            val p = parse(text) ?: return null
            return if (p.offset == null) {
                NetDateTime(p.wallClock, Kind.UNSPECIFIED)
            } else {
                toLocal(p, zone)
            }
        }

        /**
         * Newtonsoft.Json reading an ISO date with `DateTimeZoneHandling.RoundtripKind`: `Z` stays UTC, an explicit
         * offset becomes local time, no designator stays unspecified.
         */
        fun parseJson(text: String?, zone: ZoneId = ZoneId.systemDefault()): NetDateTime? {
            val p = parse(text) ?: return null
            return when {
                p.offset == null -> NetDateTime(p.wallClock, Kind.UNSPECIFIED)
                p.isUtcDesignator -> NetDateTime(p.wallClock, Kind.UTC)
                else -> toLocal(p, zone)
            }
        }
    }
}
