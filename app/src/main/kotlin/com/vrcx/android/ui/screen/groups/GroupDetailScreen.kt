package com.vrcx.android.ui.screen.groups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.vrcx.android.data.api.model.Group
import com.vrcx.android.data.api.model.GroupInstance
import com.vrcx.android.data.api.model.GroupMember
import com.vrcx.android.data.api.model.GroupPost
import com.vrcx.android.data.api.model.displayAvatarUrl
import com.vrcx.android.ui.common.relativeTime
import com.vrcx.android.ui.components.ConfirmDialog
import com.vrcx.android.ui.components.EmptyState
import com.vrcx.android.ui.components.ErrorState
import com.vrcx.android.ui.components.LoadingState
import com.vrcx.android.ui.components.VrcxCard
import com.vrcx.android.ui.components.VrcxDetailTopBar
import com.vrcx.android.ui.components.VrcxTabRow

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun GroupDetailScreen(
    viewModel: GroupDetailViewModel = hiltViewModel(),
    onUserClick: (String) -> Unit = {},
    onBack: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isActionLoading by viewModel.isActionLoading.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    // Saveable so a rotation keeps the tab the user is on: the ViewModel still
    // holds the posts and instances it loaded, they would just stop being shown.
    var selectedTab by rememberSaveable { mutableStateOf(GroupTab.MEMBERS) }
    var pendingKickUserId by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val group = (state.group as? GroupResourceState.Ready)?.value

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            VrcxDetailTopBar(title = group?.name ?: "Group", onBack = onBack)
            when (val groupState = state.group) {
                GroupResourceState.Loading,
                GroupResourceState.NotLoaded,
                -> LoadingState()

                is GroupResourceState.Error -> ErrorState(
                    groupState.message,
                    onRetry = viewModel::retryGroup,
                )

                is GroupResourceState.Ready -> GroupDetailContent(
                    modifier = Modifier.weight(1f),
                    group = groupState.value,
                    state = state,
                    selectedTab = selectedTab,
                    isActionLoading = isActionLoading,
                    membershipStatus = viewModel.membershipStatus(groupState.value),
                    canManageMembers = viewModel.canManageMembers(groupState.value),
                    canRemoveMember = { member -> viewModel.canRemoveMember(groupState.value, member) },
                    onJoinOrLeave = viewModel::joinOrLeaveGroup,
                    onRetryMembers = viewModel::retryMembers,
                    onLoadMoreMembers = viewModel::loadMoreMembers,
                    onRetryInstances = viewModel::retryInstances,
                    onRetryPosts = viewModel::retryPosts,
                    onLoadMorePosts = viewModel::loadMorePosts,
                    onTabSelected = { tab ->
                        selectedTab = tab
                        viewModel.onTabSelected(tab)
                    },
                    onUserClick = onUserClick,
                    onKickRequested = { pendingKickUserId = it },
                )
            }
        }
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }

    pendingKickUserId?.let { userId ->
        val members = (state.members as? GroupResourceState.Ready)?.value?.items.orEmpty()
        val displayName = members.firstOrNull { it.userId == userId }?.user?.displayName ?: userId
        ConfirmDialog(
            title = "Remove $displayName?",
            message = "They'll be removed from this group. They can rejoin if the group allows it.",
            confirmLabel = "Remove",
            onConfirm = {
                viewModel.kickMember(userId)
                pendingKickUserId = null
            },
            onDismiss = { pendingKickUserId = null },
        )
    }
}

@Composable
private fun GroupDetailContent(
    modifier: Modifier = Modifier,
    group: Group,
    state: GroupDetailState,
    selectedTab: GroupTab,
    isActionLoading: Boolean,
    membershipStatus: GroupMembership,
    canManageMembers: Boolean,
    canRemoveMember: (GroupMember) -> Boolean,
    onJoinOrLeave: () -> Unit,
    onRetryMembers: () -> Unit,
    onLoadMoreMembers: () -> Unit,
    onRetryInstances: () -> Unit,
    onRetryPosts: () -> Unit,
    onLoadMorePosts: () -> Unit,
    onTabSelected: (GroupTab) -> Unit,
    onUserClick: (String) -> Unit,
    onKickRequested: (String) -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (group.bannerUrl.isNotEmpty()) {
            AsyncImage(
                model = group.bannerUrl,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f)
                    .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)),
                contentScale = ContentScale.Crop,
            )
        }

        GroupHeader(group, isActionLoading, membershipStatus, onJoinOrLeave)
        GroupTabs(
            group = group,
            state = state,
            selectedTab = selectedTab,
            onTabSelected = onTabSelected,
        )

        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (selectedTab) {
                GroupTab.MEMBERS -> MembersTab(
                    state = state.members,
                    canManageMembers = canManageMembers,
                    canRemoveMember = canRemoveMember,
                    onRetry = onRetryMembers,
                    onLoadMore = onLoadMoreMembers,
                    onUserClick = onUserClick,
                    onKickRequested = onKickRequested,
                )
                GroupTab.INSTANCES -> InstancesTab(state.instances, onRetryInstances)
                GroupTab.POSTS -> PostsTab(
                    state = state.posts,
                    onRetry = onRetryPosts,
                    onLoadMore = onLoadMorePosts,
                )
            }
        }
    }
}

@Composable
private fun GroupHeader(
    group: Group,
    isActionLoading: Boolean,
    membershipStatus: GroupMembership,
    onJoinOrLeave: () -> Unit,
) {
    Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (group.iconUrl.isNotEmpty()) {
                AsyncImage(
                    model = group.iconUrl,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp).clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(group.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "${group.memberCount} members • ${group.shortCode}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isActionLoading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                GroupActionButton(membershipStatus = membershipStatus, onClick = onJoinOrLeave)
            }
        }
        if (group.description.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(group.description, style = MaterialTheme.typography.bodyMedium, maxLines = 4)
        }
    }
}

@Composable
private fun GroupTabs(
    group: Group,
    state: GroupDetailState,
    selectedTab: GroupTab,
    onTabSelected: (GroupTab) -> Unit,
) {
    val memberTotal = (state.members as? GroupResourceState.Ready)?.value?.totalCount
        ?: group.memberCount
    val instanceTotal = (state.instances as? GroupResourceState.Ready)?.value?.size
    val postPage = (state.posts as? GroupResourceState.Ready)?.value
    val postTotal = postPage?.totalCount ?: postPage?.items?.size

    VrcxTabRow(selectedTabIndex = selectedTab.ordinal) {
        GroupTab.entries.forEach { tab ->
            val count = when (tab) {
                GroupTab.MEMBERS -> memberTotal
                GroupTab.INSTANCES -> instanceTotal
                GroupTab.POSTS -> postTotal
            }
            Tab(
                selected = selectedTab == tab,
                onClick = { onTabSelected(tab) },
                text = { Text(count?.let { "${tab.label} ($it)" } ?: tab.label) },
            )
        }
    }
}

@Composable
private fun MembersTab(
    state: GroupResourceState<GroupPagedData<GroupMember>>,
    canManageMembers: Boolean,
    canRemoveMember: (GroupMember) -> Boolean,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onUserClick: (String) -> Unit,
    onKickRequested: (String) -> Unit,
) {
    when (state) {
        GroupResourceState.Loading,
        GroupResourceState.NotLoaded,
        -> LoadingState()

        is GroupResourceState.Error -> ErrorState(state.message, onRetry = onRetry)
        is GroupResourceState.Ready -> if (state.value.items.isEmpty()) {
            EmptyState("No group members")
        } else {
            // Resolved once above the list: the predicate is pure over
            // (group, member) and rescans the permission list per call.
            val removableUserIds = remember(canManageMembers, state.value.items) {
                if (!canManageMembers) {
                    emptySet()
                } else {
                    state.value.items.filter(canRemoveMember).map { it.userId }.toSet()
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.value.items, key = { member -> member.id.ifBlank { member.userId } }) { member ->
                    GroupMemberRow(
                        member = member,
                        canRemove = member.userId in removableUserIds,
                        onUserClick = onUserClick,
                        onKickRequested = onKickRequested,
                    )
                }
                if (state.value.hasMore || state.value.appendState !is GroupAppendState.Idle) {
                    item(key = "members-pagination") {
                        PaginationFooter(
                            state = state.value.appendState,
                            nextOffset = state.value.nextOffset,
                            onLoadMore = onLoadMore,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupMemberRow(
    member: GroupMember,
    canRemove: Boolean,
    onUserClick: (String) -> Unit,
    onKickRequested: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable { onUserClick(member.userId) }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = member.user?.displayAvatarUrl(),
            contentDescription = null,
            modifier = Modifier.size(40.dp).clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(member.user?.displayName ?: member.userId, style = MaterialTheme.typography.bodyLarge)
            Text(
                "Joined ${member.joinedAt.take(10)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (canRemove) {
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "Member actions")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Remove from group") },
                        leadingIcon = { Icon(Icons.Outlined.PersonRemove, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onKickRequested(member.userId)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun InstancesTab(
    state: GroupResourceState<List<GroupInstance>>,
    onRetry: () -> Unit,
) {
    when (state) {
        GroupResourceState.Loading,
        GroupResourceState.NotLoaded,
        -> LoadingState()

        is GroupResourceState.Error -> ErrorState(state.message, onRetry = onRetry)
        is GroupResourceState.Ready -> if (state.value.isEmpty()) {
            EmptyState("No active group instances")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.value, key = { it.instanceId }) { instance ->
                    VrcxCard(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(instance.world?.name ?: instance.location, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${instance.memberCount} members",
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
private fun PostsTab(
    state: GroupResourceState<GroupPagedData<GroupPost>>,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
) {
    when (state) {
        GroupResourceState.Loading,
        GroupResourceState.NotLoaded,
        -> LoadingState()

        is GroupResourceState.Error -> ErrorState(state.message, onRetry = onRetry)
        is GroupResourceState.Ready -> if (state.value.items.isEmpty()) {
            EmptyState("No group posts yet")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.value.items, key = { it.id }) { post ->
                    VrcxCard(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (post.title.isNotBlank()) {
                                Text(post.title, style = MaterialTheme.typography.titleMedium)
                            }
                            if (post.text.isNotBlank()) {
                                Text(post.text, style = MaterialTheme.typography.bodyMedium)
                            }
                            if (post.imageUrl.isNotBlank()) {
                                AsyncImage(
                                    model = post.imageUrl,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(16f / 9f)
                                        .clip(RoundedCornerShape(12.dp)),
                                    contentScale = ContentScale.Crop,
                                )
                            }
                            Text(
                                relativeTime(post.updatedAt.ifBlank { post.createdAt }),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (state.value.hasMore || state.value.appendState !is GroupAppendState.Idle) {
                    item(key = "posts-pagination") {
                        PaginationFooter(state.value.appendState, state.value.nextOffset, onLoadMore)
                    }
                }
            }
        }
    }
}

@Composable
private fun PaginationFooter(
    state: GroupAppendState,
    nextOffset: Int,
    onLoadMore: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            GroupAppendState.Idle -> {
                LaunchedEffect(nextOffset) { onLoadMore() }
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            GroupAppendState.Loading ->
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            is GroupAppendState.Error -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(state.message, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onLoadMore) { Text("Retry") }
            }
        }
    }
}

@Composable
private fun GroupActionButton(membershipStatus: GroupMembership, onClick: () -> Unit) {
    when (membershipStatus) {
        GroupMembership.MEMBER -> OutlinedButton(onClick = onClick) { Text("Leave Group") }
        GroupMembership.REQUESTED ->
            FilledTonalButton(onClick = {}, enabled = false) { Text("Request Pending") }
        GroupMembership.INVITED -> FilledTonalButton(onClick = onClick) { Text("Join Group") }
        GroupMembership.UNKNOWN -> FilledTonalButton(onClick = onClick) { Text("Request / Join") }
    }
}
