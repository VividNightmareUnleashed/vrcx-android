package com.vrcx.android.ui.screen.gamelog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vrcx.android.data.repository.FeedEntryType

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun ActivityRangeFilters(selectedRange: ActivityRange, onRangeSelect: (ActivityRange) -> Unit) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ActivityRange.entries.forEach { range ->
            FilterChip(
                selected = selectedRange == range,
                onClick = { onRangeSelect(range) },
                label = { Text(range.label) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun GameLogScopeFilters(selectedScope: GameLogScope, onScopeSelect: (GameLogScope) -> Unit) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GameLogScope.entries.forEach { scope ->
            FilterChip(
                selected = selectedScope == scope,
                onClick = { onScopeSelect(scope) },
                label = { Text(scope.label) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun FeedTypeFilters(
    vipOnly: Boolean,
    filters: Set<FeedEntryType>,
    onVipToggle: () -> Unit,
    onFilterToggle: (FeedEntryType) -> Unit,
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = vipOnly,
            onClick = onVipToggle,
            label = { Text("VIP") },
            leadingIcon = if (vipOnly) {
                { Icon(Icons.Outlined.Star, contentDescription = null) }
            } else {
                null
            },
        )
        FeedEntryType.entries.forEach { filter ->
            FilterChip(
                selected = filter in filters,
                onClick = { onFilterToggle(filter) },
                label = { Text(filter.label) },
            )
        }
    }
}
