package com.vrcx.android.ui.common

import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

private const val MINUTES_PER_HOUR = 60
private const val HOURS_PER_DAY = 24
private const val DAYS_PER_WEEK = 7
private const val ISO_DATE_LENGTH = 10

fun relativeTime(createdAt: String, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String = try {
    val instant = Instant.parse(createdAt)
    val elapsed = Duration.between(instant, now)
    val minutes = elapsed.toMinutes()
    val hours = elapsed.toHours()
    val days = elapsed.toDays()
    when {
        minutes < 1 -> "now"

        minutes < MINUTES_PER_HOUR -> "${minutes}m"

        hours < HOURS_PER_DAY -> "${hours}h"

        days < DAYS_PER_WEEK -> "${days}d"

        // Timestamps are stored in UTC; slicing the string would label the
        // row with a day the reader never experienced. The "2h"/"3d" values
        // above are elapsed time, so this has to be the local calendar date.
        else -> instant.atZone(zone).toLocalDate().toString()
    }
} catch (_: DateTimeException) {
    createdAt.take(ISO_DATE_LENGTH)
}
