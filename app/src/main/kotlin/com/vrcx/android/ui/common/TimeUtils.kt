package com.vrcx.android.ui.common

import java.time.Duration
import java.time.Instant

fun relativeTime(createdAt: String, now: Instant = Instant.now()): String {
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
            else -> createdAt.take(10)
        }
    } catch (_: Exception) {
        createdAt.take(10)
    }
}
