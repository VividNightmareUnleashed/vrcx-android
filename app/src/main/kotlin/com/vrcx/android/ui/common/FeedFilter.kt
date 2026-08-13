package com.vrcx.android.ui.common

import com.vrcx.android.data.model.formatInstanceHint
import com.vrcx.android.data.repository.FeedEntry
import com.vrcx.android.data.repository.FeedEntryType

/**
 * The filter criteria the Feed and Activity History screens share. Both screens
 * list the same [FeedEntry] rows and offer the same type / search / VIP
 * controls, so the predicate lives here instead of once per screen.
 */
data class FeedFilter(
    val types: Set<FeedEntryType>,
    val query: String,
    val vipOnly: Boolean,
    val vipFriendIds: Set<String>,
)

/** Applies [filter] to a feed page, preserving order. */
fun List<FeedEntry>.applyFeedFilter(filter: FeedFilter): List<FeedEntry> = filter { entry ->
    entry.type in filter.types &&
        (!filter.vipOnly || entry.userId in filter.vipFriendIds) &&
        (filter.query.isBlank() || entry.matchesQuery(filter.query))
}

/**
 * Search covers everything the row can render — the person, the activity
 * headline, the current and previous detail, and the instance hint — so a world
 * or instance name typed into either screen's search box matches.
 */
private fun FeedEntry.matchesQuery(query: String): Boolean =
    displayName.contains(query, ignoreCase = true) ||
        headline().contains(query, ignoreCase = true) ||
        detailText().contains(query, ignoreCase = true) ||
        previousDetailText().contains(query, ignoreCase = true) ||
        formatInstanceHint(location).contains(query, ignoreCase = true)
