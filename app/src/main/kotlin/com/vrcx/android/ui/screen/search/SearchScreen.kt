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
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vrcx.android.ui.components.EmptyState
import com.vrcx.android.ui.components.UserListItem
import com.vrcx.android.ui.components.VrcxInputField
import com.vrcx.android.ui.components.VrcxSearchBar
import com.vrcx.android.ui.components.VrcxTopBar
import com.vrcx.android.ui.components.WorldListItem
import com.vrcx.android.ui.theme.LocalWallpaperActive

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel = hiltViewModel(),
    onUserClick: (String) -> Unit = {},
    onWorldClick: (String) -> Unit = {},
    onAvatarClick: (String) -> Unit = {},
    onGroupClick: (String) -> Unit = {},
) {
    val query by viewModel.query.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()
    val users by viewModel.users.collectAsState()
    val worlds by viewModel.worlds.collectAsState()
    val avatars by viewModel.avatars.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val hasSearched by viewModel.hasSearched.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val error by viewModel.error.collectAsState()
    val currentOffset by viewModel.currentOffset.collectAsState()
    val hasMore by viewModel.hasMore.collectAsState()
    val searchUsersByBio by viewModel.searchUsersByBio.collectAsState()
    val sortUsersByLastLogin by viewModel.sortUsersByLastLogin.collectAsState()
    val worldMode by viewModel.worldMode.collectAsState()
    val includeWorldLabs by viewModel.includeWorldLabs.collectAsState()
    val worldTag by viewModel.worldTag.collectAsState()
    val avatarSearchSource by viewModel.avatarSearchSource.collectAsState()
    val avatarProviderUrl by viewModel.avatarProviderUrl.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        VrcxTopBar(title = "Search")

        VrcxSearchBar(
            query = query,
            onQueryChange = viewModel::updateQuery,
            placeholder = when (selectedTab) {
                SearchTab.USERS -> "Search users"
                SearchTab.WORLDS -> if (worldMode == WorldSearchMode.SEARCH) "Search worlds" else "Optional name filter"
                SearchTab.AVATARS -> "Search avatars"
                SearchTab.GROUPS -> "Search groups"
            },
        )

        val isWallpaperActive = LocalWallpaperActive.current
        TabRow(
            selectedTabIndex = selectedTab.ordinal,
            containerColor = MaterialTheme.colorScheme.surfaceContainer
                .let { if (isWallpaperActive) it.copy(alpha = 0.88f) else it },
        ) {
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
                        selected = searchUsersByBio,
                        onClick = { viewModel.setSearchUsersByBio(!searchUsersByBio) },
                        label = { Text("Bio") },
                    )
                    FilterChip(
                        selected = sortUsersByLastLogin,
                        onClick = { viewModel.setSortUsersByLastLogin(!sortUsersByLastLogin) },
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
                            selected = worldMode == mode,
                            onClick = { viewModel.setWorldMode(mode) },
                            label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                        )
                    }
                    FilterChip(
                        selected = includeWorldLabs,
                        onClick = { viewModel.setIncludeWorldLabs(!includeWorldLabs) },
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
                        value = worldTag,
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
                            label = { Text(source.name.lowercase().replaceFirstChar { it.uppercase() }) },
                        )
                    }
                }
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
                            value = avatarProviderUrl,
                            onValueChange = viewModel::setAvatarProviderUrl,
                            placeholder = "https://example.com/avatars",
                        )
                    }
                }
            }
            SearchTab.GROUPS -> Unit
        }

        val isEmpty = when (selectedTab) {
            SearchTab.USERS -> users.isEmpty()
            SearchTab.WORLDS -> worlds.isEmpty()
            SearchTab.AVATARS -> avatars.isEmpty()
            SearchTab.GROUPS -> groups.isEmpty()
        }

        if (isSearching) {
            com.vrcx.android.ui.components.LoadingState()
        } else if (error != null) {
            com.vrcx.android.ui.components.ErrorState(
                message = error ?: "Search failed",
                onRetry = viewModel::retry,
            )
        } else if (isEmpty) {
            if (!hasSearched) {
                EmptyState(
                    message = "Search VRChat",
                    icon = Icons.Outlined.Search,
                    subtitle = when (selectedTab) {
                        SearchTab.USERS -> "Search users by name or bio"
                        SearchTab.WORLDS -> "Browse worlds, active worlds, favorites, and your own uploads"
                        SearchTab.AVATARS -> "Search public avatars or a remote avatar provider"
                        SearchTab.GROUPS -> "Find VRChat groups"
                    },
                )
            } else {
                EmptyState(
                    message = "No results found",
                    icon = Icons.Outlined.SearchOff,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                when (selectedTab) {
                    SearchTab.USERS -> items(users, key = { it.id }) { user ->
                        UserListItem(
                            avatarUrl = user.currentAvatarThumbnailImageUrl,
                            displayName = user.displayName,
                            subtitle = user.statusDescription,
                            tags = user.tags,
                            onClick = { onUserClick(user.id) },
                        )
                    }
                    SearchTab.WORLDS -> items(worlds, key = { it.id }) { world ->
                        WorldListItem(
                            thumbnailUrl = world.thumbnailImageUrl,
                            name = world.name,
                            authorName = world.authorName,
                            occupants = world.occupants,
                            onClick = { onWorldClick(world.id) },
                        )
                    }
                    SearchTab.AVATARS -> items(avatars, key = { it.id }) { avatar ->
                        WorldListItem(
                            thumbnailUrl = avatar.thumbnailImageUrl,
                            name = avatar.name,
                            authorName = avatar.authorName,
                            onClick = { onAvatarClick(avatar.id) },
                        )
                    }
                    SearchTab.GROUPS -> items(groups, key = { it.id }) { group ->
                        WorldListItem(
                            thumbnailUrl = group.iconUrl,
                            name = group.name,
                            authorName = "${group.memberCount} members",
                            onClick = { onGroupClick(group.id) },
                        )
                    }
                }
                if (hasSearched) {
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
                                enabled = currentOffset > 0,
                            ) {
                                Text("Previous")
                            }
                            Text(
                                "Page ${currentOffset / 10 + 1}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            FilledTonalButton(
                                onClick = viewModel::nextPage,
                                enabled = hasMore,
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
