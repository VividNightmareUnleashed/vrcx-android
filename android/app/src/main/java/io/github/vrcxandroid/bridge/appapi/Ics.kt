package io.github.vrcxandroid.bridge.appapi

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** The first VEVENT of a calendar file, enough to fill an insert-event intent. */
data class IcsEvent(
    val title: String?,
    val description: String?,
    val location: String?,
    val beginMillis: Long?,
    val endMillis: Long?,
    val allDay: Boolean,
)

object Ics {
    /**
     * Upstream `OpenCalendarFile` validation: the content must start with `BEGIN:VCALENDAR` and end with
     * `END:VCALENDAR`. Trailing line breaks are tolerated (VRChat serves CRLF-terminated files).
     */
    fun isValid(content: String?): Boolean {
        if (content == null) return false
        return content.startsWith("BEGIN:VCALENDAR") && content.trimEnd().endsWith("END:VCALENDAR")
    }

    /** Unfolds the lines (RFC 5545 §3.1) and reads the first VEVENT; null when there is none. */
    fun parseFirstEvent(content: String, zone: ZoneId = ZoneId.systemDefault()): IcsEvent? {
        val lines = mutableListOf<String>()
        for (raw in content.split("\r\n", "\n", "\r")) {
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && lines.isNotEmpty()) {
                lines[lines.size - 1] = lines.last() + raw.substring(1)
            } else {
                lines += raw
            }
        }
        var inEvent = false
        val props = HashMap<String, Pair<Map<String, String>, String>>()
        for (line in lines) {
            if (line.equals("BEGIN:VEVENT", ignoreCase = true)) {
                inEvent = true
                continue
            }
            if (line.equals("END:VEVENT", ignoreCase = true)) break
            if (!inEvent) continue
            val colon = indexOfValueColon(line)
            if (colon < 0) continue
            val head = line.substring(0, colon).split(';')
            val name = head[0].uppercase()
            val params = head.drop(1).mapNotNull { p ->
                val eq = p.indexOf('=')
                if (eq < 0) null else p.substring(0, eq).uppercase() to p.substring(eq + 1).trim('"')
            }.toMap()
            if (name !in props) props[name] = params to line.substring(colon + 1)
        }
        if (!inEvent) return null
        val start = props["DTSTART"]?.let { (p, v) -> parseTime(v, p["TZID"], zone) }
        val end = props["DTEND"]?.let { (p, v) -> parseTime(v, p["TZID"], zone) }
        return IcsEvent(
            title = props["SUMMARY"]?.second?.let(::unescape),
            description = props["DESCRIPTION"]?.second?.let(::unescape),
            location = props["LOCATION"]?.second?.let(::unescape),
            beginMillis = start?.first,
            endMillis = end?.first,
            allDay = start?.second == true,
        )
    }

    /** The colon that separates name/parameters from the value (colons inside quoted parameters are skipped). */
    private fun indexOfValueColon(line: String): Int {
        var quoted = false
        for (i in line.indices) {
            when (line[i]) {
                '"' -> quoted = !quoted
                ':' -> if (!quoted) return i
            }
        }
        return -1
    }

    /** Epoch millis and whether the value was a date without time. */
    private fun parseTime(value: String, tzid: String?, zone: ZoneId): Pair<Long, Boolean>? = try {
        val v = value.trim()
        when {
            v.length == 8 -> LocalDate.parse(v, DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(zone).toInstant().toEpochMilli() to true
            v.endsWith("Z") -> LocalDateTime.parse(v.dropLast(1), BASIC_DATE_TIME).toInstant(ZoneOffset.UTC).toEpochMilli() to false
            else -> {
                val z = tzid?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: zone
                LocalDateTime.parse(v, BASIC_DATE_TIME).atZone(z).toInstant().toEpochMilli() to false
            }
        }
    } catch (e: Exception) {
        null
    }

    private val BASIC_DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    private fun unescape(text: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                when (val n = text[i + 1]) {
                    'n', 'N' -> sb.append('\n')
                    else -> sb.append(n)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
