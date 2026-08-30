package com.vrcx.android.ui.screen.groups

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.vrcx.android.data.api.model.Group
import com.vrcx.android.ui.common.valueOrNull
import com.vrcx.android.ui.components.VrcxTabRow

@Composable
internal fun GroupDetailContent(
    state: GroupDetailContentState,
    onAction: (GroupDetailUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val group = state.group
    val detail = state.detail
    Column(modifier.fillMaxWidth()) {
        GroupBanner(group)
        GroupHeader(
            group = group,
            isActionLoading = state.isActionLoading,
            membershipStatus = state.membershipStatus,
            onJoinOrLeave = { onAction(GroupDetailUiAction.JoinOrLeaveGroup) },
        )
        GroupTabs(
            group = group,
            state = detail,
            selectedTab = detail.selectedTab,
            onTabSelected = { tab -> onAction(GroupDetailUiAction.SelectTab(tab)) },
        )
        GroupSelectedTab(detail, state, onAction, Modifier.weight(1f))
    }
}

@Composable
private fun GroupBanner(group: Group) {
    if (group.bannerUrl.isEmpty()) return
    AsyncImage(
        model = group.bannerUrl,
        contentDescription = null,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(GROUP_BANNER_ASPECT_RATIO)
            .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)),
        contentScale = ContentScale.Crop,
    )
}

@Composable
private fun GroupSelectedTab(
    detail: GroupDetailState,
    content: GroupDetailContentState,
    onAction: (GroupDetailUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth()) {
        when (detail.selectedTab) {
            GroupTab.MEMBERS -> MembersTab(
                state = detail.members,
                removableMemberUserIds = content.removableMemberUserIds,
                isActionLoading = content.isActionLoading,
                removingMemberUserId = detail.removingMemberUserId,
                onAction = onAction,
            )

            GroupTab.INSTANCES -> InstancesTab(
                state = detail.instances,
                onRetry = { onAction(GroupDetailUiAction.RetryInstances) },
            )

            GroupTab.POSTS -> PostsTab(
                state = detail.posts,
                onRetry = { onAction(GroupDetailUiAction.RetryPosts) },
                onLoadMore = { onAction(GroupDetailUiAction.LoadMorePosts) },
            )
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
            GroupIcon(group)
            Column(Modifier.weight(1f)) {
                Text(
                    group.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
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
private fun GroupIcon(group: Group) {
    if (group.iconUrl.isEmpty()) return
    AsyncImage(
        model = group.iconUrl,
        contentDescription = null,
        modifier = Modifier.size(48.dp).clip(CircleShape),
        contentScale = ContentScale.Crop,
    )
    Spacer(Modifier.width(12.dp))
}

@Composable
private fun GroupTabs(
    group: Group,
    state: GroupDetailState,
    selectedTab: GroupTab,
    onTabSelected: (GroupTab) -> Unit,
) {
    val memberTotal = state.members.valueOrNull?.totalCount ?: group.memberCount
    val instanceTotal = state.instances.valueOrNull?.size
    val postPage = state.posts.valueOrNull
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
private fun GroupActionButton(membershipStatus: GroupMembership, onClick: () -> Unit) {
    when (membershipStatus) {
        GroupMembership.MEMBER -> OutlinedButton(onClick = onClick) { Text("Leave Group") }

        GroupMembership.REQUESTED ->
            FilledTonalButton(onClick = {}, enabled = false) { Text("Request Pending") }

        GroupMembership.INVITED -> FilledTonalButton(onClick = onClick) { Text("Join Group") }

        GroupMembership.UNKNOWN -> FilledTonalButton(onClick = onClick) { Text("Request / Join") }
    }
}

private const val GROUP_BANNER_ASPECT_RATIO = 3f
