package com.vrcx.android.ui.screen.groups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.vrcx.android.data.api.model.GroupMember
import com.vrcx.android.data.api.model.displayAvatarUrl
import com.vrcx.android.ui.common.LoadState
import com.vrcx.android.ui.components.EmptyState
import com.vrcx.android.ui.components.ErrorState
import com.vrcx.android.ui.components.LoadingState

@Composable
internal fun MembersTab(
    state: LoadState<GroupPagedData<GroupMember>>,
    removableMemberUserIds: Set<String>,
    isActionLoading: Boolean,
    removingMemberUserId: String?,
    onAction: (GroupDetailUiAction) -> Unit,
) {
    when (state) {
        LoadState.Loading,
        LoadState.NotLoaded,
        -> LoadingState()

        is LoadState.Failed -> ErrorState(
            state.message,
            onRetry = { onAction(GroupDetailUiAction.RetryMembers) },
        )

        is LoadState.Loaded -> if (state.value.items.isEmpty()) {
            EmptyState("No group members")
        } else {
            MembersList(state, removableMemberUserIds, isActionLoading, removingMemberUserId, onAction)
        }
    }
}

@Composable
private fun MembersList(
    state: LoadState.Loaded<GroupPagedData<GroupMember>>,
    removableMemberUserIds: Set<String>,
    isActionLoading: Boolean,
    removingMemberUserId: String?,
    onAction: (GroupDetailUiAction) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(state.value.items, key = { member -> member.id.ifBlank { member.userId } }) { member ->
            GroupMemberRow(
                member = member,
                canRemove = member.userId in removableMemberUserIds,
                actionsEnabled = !isActionLoading &&
                    state.value.appendState != GroupAppendState.Loading,
                isRemoving = member.userId == removingMemberUserId,
                onUserClick = { userId -> onAction(GroupDetailUiAction.OpenUser(userId)) },
                onKickRequested = { userId ->
                    onAction(GroupDetailUiAction.RequestMemberRemoval(userId))
                },
            )
        }
        if (state.value.hasMore || state.value.appendState !is GroupAppendState.Idle) {
            item(key = "members-pagination") {
                PaginationFooter(
                    state = state.value.appendState,
                    nextOffset = state.value.nextOffset,
                    onLoadMore = { onAction(GroupDetailUiAction.LoadMoreMembers) },
                )
            }
        }
    }
}

@Composable
private fun GroupMemberRow(
    member: GroupMember,
    canRemove: Boolean,
    actionsEnabled: Boolean,
    isRemoving: Boolean,
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
        MemberIdentity(member, Modifier.weight(1f))
        if (canRemove) {
            GroupMemberActions(member, actionsEnabled, isRemoving, onKickRequested)
        }
    }
}

@Composable
private fun MemberIdentity(member: GroupMember, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            member.user?.displayName ?: member.userId,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "Joined ${member.joinedAt.take(JOINED_DATE_LENGTH)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GroupMemberActions(
    member: GroupMember,
    actionsEnabled: Boolean,
    isRemoving: Boolean,
    onKickRequested: (String) -> Unit,
) {
    if (isRemoving) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        return
    }
    var menuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(actionsEnabled) {
        if (!actionsEnabled) menuOpen = false
    }
    Box {
        IconButton(onClick = { menuOpen = true }, enabled = actionsEnabled) {
            Icon(Icons.Outlined.MoreVert, contentDescription = "Member actions")
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Remove from group") },
                leadingIcon = { Icon(Icons.Outlined.PersonRemove, contentDescription = null) },
                enabled = actionsEnabled,
                onClick = {
                    menuOpen = false
                    onKickRequested(member.userId)
                },
            )
        }
    }
}

private const val JOINED_DATE_LENGTH = 10
