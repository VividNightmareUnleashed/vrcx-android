@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.vrcx.android.ui.screen.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vrcx.android.data.api.model.displayAvatarUrl
import com.vrcx.android.ui.components.EmptyState
import com.vrcx.android.ui.components.ErrorState
import com.vrcx.android.ui.components.LoadingState
import com.vrcx.android.ui.components.UserListItem
import com.vrcx.android.ui.components.VrcxInputField
import com.vrcx.android.ui.components.WorldListItem

internal fun searchPlaceholder(state: SearchUiState): String = when (state.selectedTab) {
    SearchTab.USERS -> "Search users"

    SearchTab.WORLDS ->
        if (state.worldMode == WorldSearchMode.SEARCH) {
            "Search worlds"
        } else {
            "Optional name filter"
        }

    SearchTab.AVATARS -> "Search avatars"

    SearchTab.GROUPS -> "Search groups"
}

@Composable
internal fun SearchFilters(state: SearchUiState, viewModel: SearchViewModel) {
    when (state.selectedTab) {
        SearchTab.USERS -> UserSearchFilters(state, viewModel)
        SearchTab.WORLDS -> WorldSearchFilters(state, viewModel)
        SearchTab.AVATARS -> AvatarSearchFilters(state, viewModel)
        SearchTab.GROUPS -> Unit
    }
}

@Composable
private fun UserSearchFilters(state: SearchUiState, viewModel: SearchViewModel) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = state.searchUsersByBio,
            onClick = { viewModel.setSearchUsersByBio(!state.searchUsersByBio) },
            label = { Text("Bio") },
        )
        FilterChip(
            selected = state.sortUsersByLastLogin,
            onClick = { viewModel.setSortUsersByLastLogin(!state.sortUsersByLastLogin) },
            label = { Text("Last Login") },
        )
    }
}

@Composable
private fun WorldSearchFilters(state: SearchUiState, viewModel: SearchViewModel) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WorldSearchMode.entries.forEach { mode ->
            FilterChip(
                selected = state.worldMode == mode,
                onClick = { viewModel.setWorldMode(mode) },
                label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
            )
        }
        FilterChip(
            selected = state.includeWorldLabs,
            onClick = { viewModel.setIncludeWorldLabs(!state.includeWorldLabs) },
            label = { Text("Labs") },
        )
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text = "Category / tag", style = MaterialTheme.typography.labelLarge)
        VrcxInputField(
            value = state.worldTag,
            onValueChange = viewModel::setWorldTag,
            placeholder = "Example: trendings or horror",
        )
    }
}

@Composable
private fun AvatarSearchFilters(state: SearchUiState, viewModel: SearchViewModel) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AvatarSearchSource.entries.forEach { source ->
            FilterChip(
                selected = state.avatarSearchSource == source,
                onClick = { viewModel.setAvatarSearchSource(source) },
                label = { Text(source.label) },
            )
        }
    }
    Text(
        text = state.avatarSearchSource.hint,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    if (state.avatarSearchSource == AvatarSearchSource.REMOTE) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = "Provider URL", style = MaterialTheme.typography.labelLarge)
            VrcxInputField(
                value = state.avatarProviderUrl,
                onValueChange = viewModel::setAvatarProviderUrl,
                placeholder = "https://example.com/avatars",
            )
        }
    }
}

@Composable
internal fun SearchResults(state: SearchUiState, viewModel: SearchViewModel, navigation: SearchNavigation) {
    val result = state.currentResult
    when {
        state.isSearching -> LoadingState()
        state.error != null -> ErrorState(message = state.error, onRetry = viewModel::retry)
        result == null || result.items.isEmpty() -> SearchEmptyState(state, viewModel)
        else -> SearchResultList(state, result, viewModel, navigation)
    }
}

@Composable
private fun SearchEmptyState(state: SearchUiState, viewModel: SearchViewModel) {
    if (!state.hasSearched) {
        EmptyState(
            message = "Search VRChat",
            icon = Icons.Outlined.Search,
            subtitle =
                when (state.selectedTab) {
                    SearchTab.USERS -> "Search users by name or bio"

                    SearchTab.WORLDS ->
                        "Browse worlds, active worlds, favorites, and your own uploads"

                    SearchTab.AVATARS -> state.avatarSearchSource.hint

                    SearchTab.GROUPS -> "Find VRChat groups"
                },
        )
    } else {
        EmptyState(
            message = "No results found",
            icon = Icons.Outlined.SearchOff,
            actionLabel = if (state.currentOffset > 0) "Previous page" else null,
            onAction = if (state.currentOffset > 0) viewModel::previousPage else null,
        )
    }
}

@Composable
private fun SearchResultList(
    state: SearchUiState,
    result: SearchResult,
    viewModel: SearchViewModel,
    navigation: SearchNavigation,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        when (result) {
            is SearchResult.Users ->
                items(result.items, key = { it.id }) { user ->
                    UserListItem(
                        avatarUrl = user.displayAvatarUrl(),
                        displayName = user.displayName,
                        subtitle = user.statusDescription,
                        tags = user.tags,
                        onClick = { navigation.onUserClick(user.id) },
                    )
                }

            is SearchResult.Worlds ->
                items(result.items, key = { it.id }) { world ->
                    WorldListItem(
                        thumbnailUrl = world.thumbnailImageUrl,
                        name = world.name,
                        authorName = world.authorName,
                        occupants = world.occupants,
                        onClick = { navigation.onWorldClick(world.id) },
                    )
                }

            is SearchResult.Avatars ->
                items(result.items, key = { it.id }) { avatar ->
                    WorldListItem(
                        thumbnailUrl = avatar.thumbnailImageUrl,
                        name = avatar.name,
                        authorName = avatar.authorName,
                        onClick = { navigation.onAvatarClick(avatar.id) },
                    )
                }

            is SearchResult.Groups ->
                items(result.items, key = { it.id }) { group ->
                    UserListItem(
                        avatarUrl = group.iconUrl,
                        displayName = group.name,
                        subtitle = "${group.memberCount} members",
                        onClick = { navigation.onGroupClick(group.id) },
                    )
                }
        }
        if (state.hasSearched) {
            item { SearchPagination(state, viewModel) }
        }
    }
}

@Composable
private fun SearchPagination(state: SearchUiState, viewModel: SearchViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = viewModel::previousPage,
            enabled = state.currentOffset > 0,
        ) {
            Text("Previous")
        }
        Text("Page ${state.pageNumber}", style = MaterialTheme.typography.bodyMedium)
        FilledTonalButton(onClick = viewModel::nextPage, enabled = state.hasMore) {
            Text("Next")
        }
    }
}
