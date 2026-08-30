package com.vrcx.android.ui.screen.gamelog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vrcx.android.data.repository.FeedEntry
import com.vrcx.android.ui.common.detailText
import com.vrcx.android.ui.common.headline
import com.vrcx.android.ui.common.previousDetailText
import com.vrcx.android.ui.common.relativeTime
import com.vrcx.android.ui.components.EmptyState
import com.vrcx.android.ui.components.VrcxCard

@Composable
internal fun GameLogEntries(
    page: GameLogPage,
    scope: GameLogScope,
    onUserClick: (String) -> Unit,
    onLoadMore: () -> Unit,
) {
    if (page.entries.isEmpty()) {
        EmptyGameLog(scope)
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            items(page.entries, key = FeedEntry::key) { entry ->
                GameLogEntryCard(entry, onUserClick)
            }
            if (page.canLoadMore) {
                item {
                    OutlinedButton(
                        onClick = onLoadMore,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    ) {
                        Text("Load More")
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyGameLog(scope: GameLogScope) {
    EmptyState(
        message = when (scope) {
            GameLogScope.CURRENT_INSTANCE -> "No instance-matched activity in this window"
            GameLogScope.CURRENT_WORLD -> "No current-world activity in this window"
            GameLogScope.ALL_ACTIVITY -> "No activity in this window"
        },
        icon = Icons.Outlined.History,
        subtitle = when (scope) {
            GameLogScope.ALL_ACTIVITY ->
                "Friend movement, status changes, and avatar updates from the selected range appear here."

            else -> "Scoped views only show entries that include location context."
        },
    )
}

@Composable
private fun GameLogEntryCard(entry: FeedEntry, onUserClick: (String) -> Unit) {
    VrcxCard(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .fillMaxWidth(),
        onClick = { onUserClick(entry.userId) },
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            GameLogEntryHeader(entry)
            Text(entry.headline(), style = MaterialTheme.typography.bodyMedium)
            EntryDetail(entry.detailText())
            EntryDetail(entry.previousDetailText(), prefix = "Previous: ")
        }
    }
}

@Composable
private fun GameLogEntryHeader(entry: FeedEntry) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            entry.displayName,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(1f))
        Text(
            relativeTime(entry.createdAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EntryDetail(detail: String, prefix: String = "") {
    if (detail.isNotBlank()) {
        Text(
            text = prefix + detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
