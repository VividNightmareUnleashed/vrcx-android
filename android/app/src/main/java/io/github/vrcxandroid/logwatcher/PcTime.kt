package io.github.vrcxandroid.logwatcher

import io.github.vrcxandroid.bridge.DotNetException
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** .NET `DateTime.Ticks` arithmetic (100 ns units since 0001-01-01T00:00:00). */
internal object Ticks {
    const val UNIX_EPOCH = 621_355_968_000_000_000L
    const val MAX = 3_155_378_975_999_999_999L
    const val PER_MS = 10_000L
    const val PER_SECOND = 10_000_000L
    const val PER_DAY = 864_000_000_000L
    private const val EPOCH_DAY_OF_0001 = -719_162L

    fun fromEpochMs(ms: Long): Long = ms * PER_MS + UNIX_EPOCH

    fun fromEpochSecond(sec: Long, nanos: Int = 0): Long = sec * PER_SECOND + UNIX_EPOCH + nanos / 100

    /** `DateTime.ToUniversalTime()` clamps to MinValue/MaxValue instead of overflowing. */
    fun clamp(t: Long): Long = if (t < 0) 0 else if (t > MAX) MAX else t

    /** `dt.ToString("yyyy'-'MM'-'dd'T'HH':'mm':'ss'.'fff'Z'", InvariantCulture)` (sub-millisecond ticks truncated). */
    fun formatIso(ticks: Long): String {
        val days = ticks / PER_DAY
        var rem = ticks - days * PER_DAY
        val date = LocalDate.ofEpochDay(days + EPOCH_DAY_OF_0001)
        val hour = (rem / 36_000_000_000L).toInt()
        rem -= hour * 36_000_000_000L
        val minute = (rem / 600_000_000L).toInt()
        rem -= minute * 600_000_000L
        val second = (rem / PER_SECOND).toInt()
        rem -= second * PER_SECOND
        val ms = (rem / PER_MS).toInt()
        val sb = StringBuilder(24)
        pad(sb, date.year, 4).append('-')
        pad(sb, date.monthValue, 2).append('-')
        pad(sb, date.dayOfMonth, 2).append('T')
        pad(sb, hour, 2).append(':')
        pad(sb, minute, 2).append(':')
        pad(sb, second, 2).append('.')
        pad(sb, ms, 3).append('Z')
        return sb.toString()
    }

    private fun pad(sb: StringBuilder, v: Int, width: Int): StringBuilder {
        val s = v.toString()
        for (i in s.length until width) sb.append('0')
        return sb.append(s)
    }
}

/**
 * The VRChat PC's local time zone, applied the way `DateTime.ToUniversalTime()` applies `TimeZoneInfo.Local`:
 * ambiguous (fall-back) times use the standard offset, which is the later offset, and times in
 * a spring-forward gap resolve to the same instant as .NET's standard-offset rule.
 */
internal class PcZone(val zone: ZoneId) {
    private var cacheKey: String? = null
    private var cacheTicks: Long = 0
    private var cacheValid = false

    fun localToUtcTicks(ldt: LocalDateTime): Long {
        val zdt = ldt.atZone(zone).withLaterOffsetAtOverlap()
        return Ticks.clamp(Ticks.fromEpochSecond(zdt.toEpochSecond(), zdt.nano))
    }

    /**
     * `DateTime.TryParseExact(line.Substring(0, 19), "yyyy.MM.dd HH:mm:ss", InvariantCulture, None, out d)` followed by
     * `d.ToUniversalTime()`, as UTC ticks, or null when the stamp does not parse. Throws like `Substring(0, 19)` when
     * the line is shorter than 19 characters. The last result is cached (consecutive lines share their second).
     */
    fun lineStampToUtcTicks(line: String): Long? {
        if (line.length < 19) throw StringIndexOutOfBoundsException("startIndex + length > this.length")
        val key = cacheKey
        if (key != null && line.regionMatches(0, key, 0, 19)) return if (cacheValid) cacheTicks else null
        val stamp = line.substring(0, 19)
        val ldt = LogStamp.parse(stamp)
        cacheKey = stamp
        cacheValid = ldt != null
        if (ldt != null) cacheTicks = localToUtcTicks(ldt)
        return if (ldt != null) cacheTicks else null
    }

    companion object {
        /**
         * The zone for a companion's `info.tz`: the IANA rules when the PC zone observes DST
         * (IANA id from the companion, else mapped from the Windows id), a fixed base offset when it does not
         * (Windows "adjust for daylight saving time automatically" off, or a zone without DST), and the current
         * offset as a last resort.
         */
        fun of(info: CompanionInfo?, warn: (String) -> Unit): PcZone {
            if (info == null) return PcZone(ZoneId.systemDefault())
            try {
                if (!info.tzSupportsDst) return PcZone(ZoneOffset.ofTotalSeconds(info.tzBaseUtcOffsetMin * 60))
                val iana = info.tzIanaId?.takeIf { it.isNotBlank() } ?: info.tzWindowsId?.let { WindowsZones.toIana(it) }
                if (iana != null) {
                    try {
                        return PcZone(ZoneId.of(iana))
                    } catch (e: DateTimeException) {
                        warn("unknown IANA zone $iana")
                    }
                }
                warn("no IANA zone for Windows zone ${info.tzWindowsId}; using the fixed current offset")
                return PcZone(ZoneOffset.ofTotalSeconds(info.tzCurrentUtcOffsetMin * 60))
            } catch (e: DateTimeException) {
                warn("invalid PC time zone offset: ${e.message}")
                return PcZone(ZoneOffset.UTC)
            }
        }
    }
}

/** Strict `yyyy.MM.dd HH:mm:ss` parsing with .NET `TryParseExact` semantics (ASCII digits only, real dates). */
internal object LogStamp {
    fun parse(s: String): LocalDateTime? {
        if (s.length != 19) return null
        if (s[4] != '.' || s[7] != '.' || s[10] != ' ' || s[13] != ':' || s[16] != ':') return null
        val year = digits(s, 0, 4)
        val month = digits(s, 5, 2)
        val day = digits(s, 8, 2)
        val hour = digits(s, 11, 2)
        val minute = digits(s, 14, 2)
        val second = digits(s, 17, 2)
        if (year < 1 || month < 1 || month > 12 || day < 1 || hour < 0 || hour > 23 || minute < 0 || minute > 59 ||
            second < 0 || second > 59
        ) return null
        if (day > LocalDate.of(year, month, 1).lengthOfMonth()) return null
        return LocalDateTime.of(year, month, day, hour, minute, second)
    }

    private fun digits(s: String, at: Int, n: Int): Int {
        var v = 0
        for (i in at until at + n) {
            val c = s[i]
            if (c < '0' || c > '9') return -1
            v = v * 10 + (c - '0')
        }
        return v
    }
}

/**
 * `DateTime.Parse(date, CultureInfo.InvariantCulture, DateTimeStyles.None).ToUniversalTime()` for the ISO-8601
 * forms the frontend sends to `SetDateTill` (`Date.toJSON()`): `yyyy-MM-dd`, optionally
 * followed by `T` or spaces and `HH:mm[:ss[.fraction]]`, optionally followed by `Z` or an offset `±HH[[:]mm]`, with
 * surrounding white space. A value without a zone designator is PC-local time. Other formats .NET would accept are
 * rejected with `FormatException`, as are invalid dates.
 */
internal object DotNetDateParse {
    private val ISO = Regex(
        """^\s*(\d{4})-(\d{1,2})-(\d{1,2})(?:(?:T|\s+)(\d{1,2}):(\d{1,2})(?::(\d{1,2})(?:\.(\d+))?)?)?\s*(?:([Zz])|([+-])(\d{1,2})(?::?(\d{2}))?)?\s*$""",
    )

    fun toUtcTicks(s: String, zone: PcZone): Long {
        val m = ISO.matchEntire(s) ?: throw invalid(s)
        val g = m.groupValues
        val ldt = try {
            LocalDateTime.of(
                g[1].toInt(), g[2].toInt(), g[3].toInt(),
                if (g[4].isEmpty()) 0 else g[4].toInt(),
                if (g[5].isEmpty()) 0 else g[5].toInt(),
                if (g[6].isEmpty()) 0 else g[6].toInt(),
            )
        } catch (e: DateTimeException) {
            throw invalid(s)
        }
        val fractionTicks = fractionTicks(g[7])
        val utc = when {
            g[8].isNotEmpty() -> Ticks.fromEpochSecond(ldt.toEpochSecond(ZoneOffset.UTC))
            g[9].isNotEmpty() -> {
                val h = g[10].toInt()
                val mm = if (g[11].isEmpty()) 0 else g[11].toInt()
                if (h > 14 || mm > 59) throw invalid(s)
                val sign = if (g[9] == "-") -1 else 1
                Ticks.fromEpochSecond(ldt.toEpochSecond(ZoneOffset.UTC)) - sign * (h * 60L + mm) * 60 * Ticks.PER_SECOND
            }
            else -> zone.localToUtcTicks(ldt)
        }
        return Ticks.clamp(utc + fractionTicks)
    }

    /** .NET accumulates the fraction digits in a double and rounds `fraction * TicksPerSecond` half to even. */
    private fun fractionTicks(digits: String): Long {
        if (digits.isEmpty()) return 0
        var result = 0.0
        var decimalBase = 0.1
        for (c in digits) {
            result += (c - '0') * decimalBase
            decimalBase *= 0.1
        }
        return Math.rint(result * Ticks.PER_SECOND).toLong()
    }

    private fun invalid(s: String) =
        DotNetException("FormatException", "The string '$s' was not recognized as a valid DateTime.")
}
