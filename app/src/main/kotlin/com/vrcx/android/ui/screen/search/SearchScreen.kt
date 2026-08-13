package com.vrcx.android.ui.screen.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vrcx.android.data.api.model.displayAvatarUrl
import com.vrcx.android.ui.components.EmptyState
import com.vrcx.android.ui.components.ErrorState
import com.vrcx.android.ui.components.LoadingState
import com.vrcx.android.ui.components.UserListItem
import com.vrcx.android.ui.components.VrcxInputField
import com.vrcx.android.ui.components.VrcxSearchBar
import com.vrcx.android.ui.components.VrcxTabRow
import com.vrcx.android.ui.components.VrcxTopBar
import com.vrcx.android.ui.components.WorldListItem

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel = hiltViewModel(),
    onUserClick: (String) -> Unit = {},
    onWorldClick: (String) -> Unit = {},
    onAvatarClick: (String) -> Unit = {},
    onGroupClick: (String) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedTab = state.selectedTab
    val avatarSearchSource = state.avatarSearchSource

    Column(modifier = Modifier.fillMaxSize()) {
        VrcxTopBar(title = "Search")

        VrcxSearchBar(
            query = state.query,
            onQueryChange = viewModel::updateQuery,
            placeholder = when (selectedTab) {
                SearchTab.USERS -> "Search users"
                SearchTab.WORLDS -> if (state.worldMode == WorldSearchMode.SEARCH) "Search worlds" else "Optional name filter"
                SearchTab.AVATARS -> "Search avatars"
                SearchTab.GROUPS -> "Search groups"
            },
        )

        VrcxTabRow(selectedTabIndex = selectedTab.ordinal) {
            SearchTab.entries.forEach { tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { viewModel.selectTab(tab) },
                    text = { Text(tab.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }

        when (selectedTab) {
            SearchTab.USERS -> {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
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
            SearchTab.WORLDS -> {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "Category / tag",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    VrcxInputField(
                        value = state.worldTag,
                        onValueChange = viewModel::setWorldTag,
                        placeholder = "Example: trendings or horror",
                    )
                }
            }
            SearchTab.AVATARS -> {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AvatarSearchSource.entries.forEach { source ->
                        FilterChip(
                            selected = avatarSearchSource == source,
                            onClick = { viewModel.setAvatarSearchSource(source) },
                            label = { Text(source.label) },
                        )
                    }
                }
                Text(
                    avatarSearchSource.hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                if (avatarSearchSource == AvatarSearchSource.REMOTE) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "Provider URL",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        VrcxInputField(
                            value = state.avatarProviderUrl,
                            onValueChange = viewModel::setAvatarProviderUrl,
                            placeholder = "https://example.com/avatars",
                        )
                    }
                }
            }
            SearchTab.GROUPS -> Unit
        }

        val result = state.currentResult
        val error = state.error

        if (state.isSearching) {
            LoadingState()
        } else if (error != null) {
            ErrorState(message = error, onRetry = viewModel::retry)
        } else if (result == null || result.items.isEmpty()) {
            if (!state.hasSearched) {
                EmptyState(
                    message = "Search VRChat",
                    icon = Icons.Outlined.Search,
                    subtitle = when (selectedTab) {
                        SearchTab.USERS -> "Search users by name or bio"
                        SearchTab.WORLDS -> "Browse worlds, active worlds, favorites, and your own uploads"
                        SearchTab.AVATARS -> avatarSearchSource.hint
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
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                when (result) {
                    is SearchResult.Users -> items(result.items, key = { it.id }) { user ->
                        UserListItem(
                            avatarUrl = user.displayAvatarUrl(),
                            displayName = user.displayName,
                            subtitle = user.statusDescription,
                            tags = user.tags,
                            onClick = { onUserClick(user.id) },
                        )
                    }
                    is SearchResult.Worlds -> items(result.items, key = { it.id }) { world ->
                        WorldListItem(
                            thumbnailUrl = world.thumbnailImageUrl,
                            name = world.name,
                            authorName = world.authorName,
                            occupants = world.occupants,
                            onClick = { onWorldClick(world.id) },
                        )
                    }
                    is SearchResult.Avatars -> items(result.items, key = { it.id }) { avatar ->
                        WorldListItem(
                            thumbnailUrl = avatar.thumbnailImageUrl,
                            name = avatar.name,
                            authorName = avatar.authorName,
                            onClick = { onAvatarClick(avatar.id) },
                        )
                    }
                    is SearchResult.Groups -> items(result.items, key = { it.id }) { group ->
                        UserListItem(
                            avatarUrl = group.iconUrl,
                            displayName = group.name,
                            subtitle = "${group.memberCount} members",
                            onClick = { onGroupClick(group.id) },
                        )
                    }
                }
                if (state.hasSearched) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedButton(
                                onClick = viewModel::previousPage,
                                enabled = state.currentOffset > 0,
                            ) {
                                Text("Previous")
                            }
                            Text(
                                "Page ${state.pageNumber}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            FilledTonalButton(
                                onClick = viewModel::nextPage,
                                enabled = state.hasMore,
                            ) {
                                Text("Next")
                            }
                        }
                    }
                }
            }
        }
    }
}
