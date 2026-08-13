package com.vrcx.android.ui.common

import com.vrcx.android.data.model.formatInstanceHint
import com.vrcx.android.data.model.parseWorldId
import com.vrcx.android.data.repository.FeedEntry
import com.vrcx.android.data.repository.FeedEntryType

/**
 * Display formatting for [FeedEntry], shared by the Feed, Dashboard, and
 * Activity History screens so a label change lives in one place instead of
 * three drifting `when (type)` copies.
 */

/** Short headline for the row (Activity History's first line). */
fun FeedEntry.headline(): String = when (type) {
    FeedEntryType.GPS -> "Location update"
    FeedEntryType.STATUS -> "Status update"
    FeedEntryType.BIO -> "Bio updated"
    FeedEntryType.AVATAR -> "Avatar changed"
    FeedEntryType.ONLINE -> "Came online"
    FeedEntryType.OFFLINE -> "Went offline"
}

/** The primary detail text for the row (world, status, bio, avatar name). */
fun FeedEntry.detailText(): String = when (type) {
    FeedEntryType.GPS -> worldName.ifBlank { formatLocationLabel(location) }
    FeedEntryType.STATUS -> statusText(status, statusDescription).ifBlank { "Status changed" }
    FeedEntryType.BIO -> bio
    FeedEntryType.AVATAR -> avatarName.ifBlank { "Avatar updated" }
    FeedEntryType.ONLINE, FeedEntryType.OFFLINE -> when {
        worldName.isNotBlank() -> worldName
        location.isNotBlank() -> formatLocationLabel(location)
        type == FeedEntryType.OFFLINE -> "Offline"
        else -> "Presence update"
    }
}

/** The previous value for the row, where one is tracked (Activity History). */
fun FeedEntry.previousDetailText(): String = when (type) {
    FeedEntryType.GPS -> formatLocationLabel(previousLocation)
    FeedEntryType.STATUS -> statusText(previousStatus, previousStatusDescription)
    FeedEntryType.BIO -> previousBio
    else -> ""
}

/** One-line activity summary for the Feed and Dashboard lists. */
fun FeedEntry.activityLabel(): String = when (type) {
    FeedEntryType.GPS -> "Moved to ${detailText()}"
    FeedEntryType.STATUS -> "Status: ${statusText(status, statusDescription)}"
    FeedEntryType.BIO -> "Updated bio"
    FeedEntryType.AVATAR -> "Changed avatar"
    FeedEntryType.ONLINE -> "Came online"
    FeedEntryType.OFFLINE -> "Went offline"
}

private fun statusText(status: String, description: String): String =
    listOf(status, description).filter { it.isNotBlank() }.joinToString(": ")

private fun formatLocationLabel(location: String): String {
    val worldId = parseWorldId(location)
    val instanceHint = formatInstanceHint(location)
    return when {
        worldId.isNotBlank() && instanceHint.isNotBlank() -> "$worldId • $instanceHint"
        worldId.isNotBlank() -> worldId
        location.isBlank() -> ""
        else -> location
    }
}
