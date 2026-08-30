package com.vrcx.android.ui.screen.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.vrcx.android.data.api.model.Avatar
import com.vrcx.android.data.api.model.Group
import com.vrcx.android.data.api.model.VrcUser
import com.vrcx.android.data.api.model.World
import com.vrcx.android.data.api.model.displayAvatarUrl
import com.vrcx.android.data.model.friendStateOf
import com.vrcx.android.data.repository.FavoriteWorldSection
import com.vrcx.android.data.repository.canonicalGroupId
import com.vrcx.android.ui.common.prettyVisibility
import com.vrcx.android.ui.components.EmptyState
import com.vrcx.android.ui.components.LoadingState
import com.vrcx.android.ui.components.SectionHeader
import com.vrcx.android.ui.components.UserListItem
import com.vrcx.android.ui.components.VrcxCard
import com.vrcx.android.ui.components.WorldListItem

@Composable
internal fun UserDetailTabContent(
    state: UserDetailUiState,
    user: VrcUser,
    onAction: (UserDetailUiAction) -> Unit,
    onUserClick: (String) -> Unit,
    onWorldClick: (String) -> Unit,
    onGroupClick: (String) -> Unit,
    onAvatarClick: (String) -> Unit,
) {
    // Keep cached rows visible when a previously loaded tab is selected again.
    val isTabLoading = state.selectedTab in state.loadingTabs && state.selectedTab !in state.loadedTabs
    when (state.selectedTab) {
        UserDetailTab.INFO -> UserDetailInfoTab(state, user, onAction, onWorldClick)

        UserDetailTab.MUTUALS -> if (isTabLoading) {
            LoadingState()
        } else {
            MutualFriendsTab(state.mutualFriends, onUserClick)
        }

        UserDetailTab.GROUPS -> if (isTabLoading) {
            LoadingState()
        } else {
            GroupsTab(state.userGroups, onGroupClick)
        }

        UserDetailTab.WORLDS -> if (isTabLoading) {
            LoadingState()
        } else {
            WorldsTab(state.userWorlds, onWorldClick)
        }

        UserDetailTab.AVATARS -> if (isTabLoading) {
            LoadingState()
        } else {
            AvatarsTab(state.userAvatars, onAvatarClick)
        }

        UserDetailTab.FAVORITE_WORLDS -> if (isTabLoading) {
            LoadingState()
        } else {
            FavoriteWorldsTab(
                sections = state.favoriteWorldSections,
                selectedTag = state.selectedFavoriteWorldTag,
                onSelectGroup = { onAction(UserDetailUiAction.SelectFavoriteWorldGroup(it)) },
                onWorldClick = onWorldClick,
            )
        }
    }
}

@Composable
private fun MutualFriendsTab(mutualFriends: List<VrcUser>, onUserClick: (String) -> Unit) {
    if (mutualFriends.isEmpty()) {
        EmptyState(message = "No mutual friends")
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            items(mutualFriends, key = { it.id }) { friend ->
                UserListItem(
                    avatarUrl = friend.displayAvatarUrl().ifBlank { null },
                    displayName = friend.displayName,
                    subtitle = friend.statusDescription.ifBlank { friend.status },
                    tags = friend.tags,
                    status = friend.status,
                    state = friendStateOf(friend.location),
                    onClick = { onUserClick(friend.id) },
                )
            }
        }
    }
}

@Composable
private fun GroupsTab(groups: List<Group>, onGroupClick: (String) -> Unit) {
    if (groups.isEmpty()) {
        EmptyState(message = "No groups")
    } else {
        LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(groups, key = { it.id }) { group ->
                val groupId = group.canonicalGroupId()
                VrcxCard(onClick = { onGroupClick(groupId) }) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (group.iconUrl.isNotEmpty()) {
                            AsyncImage(
                                model = group.iconUrl,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp).clip(CircleShape),
                                contentScale = ContentScale.Crop,
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                        Column {
                            Text(group.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${group.memberCount} members",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorldsTab(worlds: List<World>, onWorldClick: (String) -> Unit, modifier: Modifier = Modifier) {
    if (worlds.isEmpty()) {
        EmptyState(message = "No worlds")
    } else {
        LazyColumn(modifier.fillMaxSize()) {
            items(worlds, key = { it.id }) { world ->
                WorldListItem(
                    thumbnailUrl = world.thumbnailImageUrl,
                    name = world.name,
                    authorName = world.authorName,
                    occupants = world.occupants,
                    onClick = { onWorldClick(world.id) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FavoriteWorldsTab(
    sections: List<FavoriteWorldSection>,
    selectedTag: String?,
    onSelectGroup: (String) -> Unit,
    onWorldClick: (String) -> Unit,
) {
    if (sections.isEmpty()) {
        EmptyState(message = "No public favorite worlds")
        return
    }

    val selectedSection = sections.firstOrNull { it.tag == selectedTag } ?: sections.first()
    Column(Modifier.fillMaxSize()) {
        SectionHeader(title = "Favorite World Groups")
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            sections.forEach { section ->
                FilterChip(
                    selected = section.tag == selectedSection.tag,
                    onClick = { onSelectGroup(section.tag) },
                    label = { Text("${section.displayName} (${section.worlds.size})") },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = buildString {
                append(selectedSection.displayName)
                if (selectedSection.visibility.isNotBlank()) {
                    append(" • ")
                    append(selectedSection.visibility.prettyVisibility())
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(8.dp))
        WorldsTab(
            worlds = selectedSection.worlds,
            onWorldClick = onWorldClick,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun AvatarsTab(avatars: List<Avatar>, onAvatarClick: (String) -> Unit) {
    if (avatars.isEmpty()) {
        EmptyState(message = "No avatars")
    } else {
        LazyColumn(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(avatars, key = { it.id }) { avatar ->
                VrcxCard(onClick = { onAvatarClick(avatar.id) }) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = avatar.thumbnailImageUrl.ifEmpty { avatar.imageUrl },
                            contentDescription = null,
                            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)),
                            contentScale = ContentScale.Crop,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(avatar.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                avatar.releaseStatus.replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
