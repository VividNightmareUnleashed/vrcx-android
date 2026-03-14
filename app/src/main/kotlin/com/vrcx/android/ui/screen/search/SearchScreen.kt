package com.vrcx.android.ui.screen.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.vrcx.android.ui.components.EmptyState
import com.vrcx.android.ui.components.UserListItem
import com.vrcx.android.ui.components.VrcxSearchBar
import com.vrcx.android.ui.components.VrcxTopBar
import com.vrcx.android.ui.components.WorldListItem
import com.vrcx.android.ui.theme.LocalWallpaperActive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel = hiltViewModel(),
    onUserClick: (String) -> Unit = {},
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

    Column(modifier = Modifier.fillMaxSize()) {
        VrcxTopBar(title = "Search")

        VrcxSearchBar(
            query = query,
            onQueryChange = viewModel::updateQuery,
            placeholder = "Search VRChat",
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
                onRetry = { viewModel.selectTab(selectedTab) },
            )
        } else if (isEmpty) {
            if (!hasSearched) {
                EmptyState(
                    message = "Search VRChat",
                    icon = Icons.Outlined.Search,
                    subtitle = "Find users, worlds, avatars, and groups",
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
                        )
                    }
                    SearchTab.AVATARS -> items(avatars, key = { it.id }) { avatar ->
                        WorldListItem(
                            thumbnailUrl = avatar.thumbnailImageUrl,
                            name = avatar.name,
                            authorName = avatar.authorName,
                        )
                    }
                    SearchTab.GROUPS -> items(groups, key = { it.id }) { group ->
                        WorldListItem(
                            thumbnailUrl = group.iconUrl,
                            name = group.name,
                            authorName = "${group.memberCount} members",
                        )
                    }
                }
            }
        }
    }
}
