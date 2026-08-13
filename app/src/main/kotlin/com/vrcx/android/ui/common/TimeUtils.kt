package com.vrcx.android.ui.common

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

fun relativeTime(
    createdAt: String,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    return try {
        val instant = Instant.parse(createdAt)
        val elapsed = Duration.between(instant, now)
        val minutes = elapsed.toMinutes()
        val hours = elapsed.toHours()
        val days = elapsed.toDays()
        when {
            minutes < 1 -> "now"
            minutes < 60 -> "${minutes}m"
            hours < 24 -> "${hours}h"
            days < 7 -> "${days}d"
            // Timestamps are stored in UTC; slicing the string would label the
            // row with a day the reader never experienced. The "2h"/"3d" values
            // above are elapsed time, so this has to be the local calendar date.
            else -> instant.atZone(zone).toLocalDate().toString()
        }
    } catch (_: Exception) {
        createdAt.take(10)
    }
}
