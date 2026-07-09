package com.vrcx.android.ui.common

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

fun relativeTime(createdAt: String): String {
    return try {
        val instant = Instant.parse(createdAt)
        val now = Clock.System.now()
        val diff = now - instant
        val minutes = diff.inWholeMinutes
        val hours = diff.inWholeHours
        val days = diff.inWholeDays
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
