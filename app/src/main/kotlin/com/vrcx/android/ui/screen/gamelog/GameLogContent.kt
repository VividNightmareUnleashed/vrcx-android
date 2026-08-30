package com.vrcx.android.ui.screen.gamelog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vrcx.android.data.repository.FeedEntryType
import com.vrcx.android.ui.components.VrcxDetailTopBar
import com.vrcx.android.ui.components.VrcxSearchBar

@Composable
internal fun GameLogContent(
    state: GameLogUiState,
    onBack: () -> Unit,
    onSearchChange: (String) -> Unit,
    onVipToggle: () -> Unit,
    onFilterToggle: (FeedEntryType) -> Unit,
    onRangeSelect: (ActivityRange) -> Unit,
    onScopeSelect: (GameLogScope) -> Unit,
    onUserClick: (String) -> Unit,
    onLoadMore: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        // Android cannot tail VRChat's client log; this route owns the app's
        // persisted friend-activity history instead.
        VrcxDetailTopBar(title = "Activity History", onBack = onBack)
        Text(
            text = "Friend activity history built from location, presence, and profile updates.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        VrcxSearchBar(
            query = state.controls.searchQuery,
            onQueryChange = onSearchChange,
            placeholder = "Search people, worlds, or activity",
            modifier = Modifier.fillMaxWidth(),
        )
        ActivityRangeFilters(state.controls.range, onRangeSelect)
        GameLogScopeFilters(state.controls.scope, onScopeSelect)
        if (state.page.showScopeHint) {
            ScopeHint()
        }
        FeedTypeFilters(
            vipOnly = state.controls.vipOnly,
            filters = state.controls.filters,
            onVipToggle = onVipToggle,
            onFilterToggle = onFilterToggle,
        )
        GameLogEntries(
            page = state.page,
            scope = state.controls.scope,
            onUserClick = onUserClick,
            onLoadMore = onLoadMore,
        )
    }
}

@Composable
private fun ScopeHint() {
    Text(
        text = "Scoped views only include entries with world or instance context.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}
