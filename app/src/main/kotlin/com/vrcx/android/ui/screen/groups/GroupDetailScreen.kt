package com.vrcx.android.ui.screen.groups

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vrcx.android.data.api.model.Group
import com.vrcx.android.data.api.model.GroupMember
import com.vrcx.android.ui.common.LoadState
import com.vrcx.android.ui.common.valueOrNull
import com.vrcx.android.ui.components.ConfirmDialog
import com.vrcx.android.ui.components.ErrorState
import com.vrcx.android.ui.components.LoadingState
import com.vrcx.android.ui.components.VrcxDetailTopBar

private data class GroupDetailNotice(val message: String, val source: GroupDetailMessageSource)

private class GroupDetailActionHandler(
    private val viewModel: GroupDetailViewModel,
    private val onUserClick: (String) -> Unit,
    private val onMemberRemovalRequested: (String) -> Unit,
) {
    fun handle(action: GroupDetailUiAction) {
        when (action) {
            GroupDetailUiAction.RetryGroup -> viewModel.dispatch(GroupDetailIntent.RetryGroup)

            GroupDetailUiAction.JoinOrLeaveGroup ->
                viewModel.dispatch(GroupDetailIntent.JoinOrLeaveGroup)

            GroupDetailUiAction.RetryMembers -> viewModel.dispatch(GroupDetailIntent.RetryMembers)

            GroupDetailUiAction.LoadMoreMembers -> viewModel.dispatch(GroupDetailIntent.LoadMoreMembers)

            GroupDetailUiAction.RetryInstances -> viewModel.dispatch(GroupDetailIntent.RetryInstances)

            GroupDetailUiAction.RetryPosts -> viewModel.dispatch(GroupDetailIntent.RetryPosts)

            GroupDetailUiAction.LoadMorePosts -> viewModel.dispatch(GroupDetailIntent.LoadMorePosts)

            is GroupDetailUiAction.SelectTab ->
                viewModel.dispatch(GroupDetailIntent.SelectTab(action.tab))

            is GroupDetailUiAction.OpenUser -> onUserClick(action.userId)

            is GroupDetailUiAction.RequestMemberRemoval ->
                onMemberRemovalRequested(action.userId)
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun GroupDetailScreen(
    viewModel: GroupDetailViewModel = hiltViewModel(),
    onUserClick: (String) -> Unit = {},
    onBack: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingKickUserId by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val actionHandler = remember(viewModel, onUserClick) {
        GroupDetailActionHandler(viewModel, onUserClick) { userId ->
            pendingKickUserId = userId
        }
    }

    GroupDetailNoticeEffect(state.visibleNotice(), snackbarHostState, viewModel::dispatch)
    GroupDetailLayout(state, snackbarHostState, onBack, actionHandler::handle)
    MemberRemovalDialog(
        userId = pendingKickUserId,
        members = state.members.valueOrNull?.items.orEmpty(),
        onConfirm = { userId ->
            viewModel.dispatch(GroupDetailIntent.KickMember(userId))
            pendingKickUserId = null
        },
        onDismiss = { pendingKickUserId = null },
    )
}

@Composable
private fun GroupDetailNoticeEffect(
    notice: GroupDetailNotice?,
    snackbarHostState: SnackbarHostState,
    dispatch: (GroupDetailIntent) -> Unit,
) {
    LaunchedEffect(notice) {
        notice?.let {
            snackbarHostState.showSnackbar(it.message)
            dispatch(GroupDetailIntent.ClearMessage(it.source))
        }
    }
}

@Composable
private fun GroupDetailLayout(
    state: GroupDetailState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onAction: (GroupDetailUiAction) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            VrcxDetailTopBar(
                title = state.group.valueOrNull?.name ?: "Group",
                onBack = onBack,
            )
            GroupDetailBody(state, onAction, Modifier.weight(1f))
        }
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun GroupDetailBody(
    state: GroupDetailState,
    onAction: (GroupDetailUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (val groupState = state.group) {
        LoadState.Loading,
        LoadState.NotLoaded,
        -> LoadingState()

        is LoadState.Failed -> ErrorState(
            groupState.message,
            onRetry = { onAction(GroupDetailUiAction.RetryGroup) },
        )

        is LoadState.Loaded -> GroupDetailContent(
            state = state.toContentState(groupState.value),
            onAction = onAction,
            modifier = modifier,
        )
    }
}

@Composable
private fun MemberRemovalDialog(
    userId: String?,
    members: List<GroupMember>,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (userId == null) return
    val displayName = members.firstOrNull { it.userId == userId }?.user?.displayName ?: userId
    ConfirmDialog(
        title = "Remove $displayName?",
        message = "They'll be removed from this group. They can rejoin if the group allows it.",
        confirmLabel = "Remove",
        onConfirm = { onConfirm(userId) },
        onDismiss = onDismiss,
    )
}

private fun GroupDetailState.visibleNotice(): GroupDetailNotice? =
    message?.let { GroupDetailNotice(it, GroupDetailMessageSource.ACTION) }
        ?: group.staleNotice(GroupDetailMessageSource.GROUP)
        ?: when (selectedTab) {
            GroupTab.MEMBERS -> members.staleNotice(GroupDetailMessageSource.MEMBERS)
            GroupTab.INSTANCES -> instances.staleNotice(GroupDetailMessageSource.INSTANCES)
            GroupTab.POSTS -> posts.staleNotice(GroupDetailMessageSource.POSTS)
        }

private fun LoadState<*>.staleNotice(source: GroupDetailMessageSource): GroupDetailNotice? =
    (this as? LoadState.Loaded<*>)?.staleError?.let { GroupDetailNotice(it, source) }

private fun GroupDetailState.toContentState(group: Group): GroupDetailContentState {
    val membersAreStable = (members as? LoadState.Loaded)?.isRefreshing != true
    val removableMemberUserIds = if (membersAreStable) {
        GroupMembershipPolicy.removableMemberUserIds(
            group,
            members.valueOrNull?.items.orEmpty(),
        )
    } else {
        emptySet()
    }
    return GroupDetailContentState(
        group = group,
        detail = this,
        membershipStatus = GroupMembershipPolicy.status(group),
        isActionLoading = isActionLoading ||
            (this.group as? LoadState.Loaded<*>)?.isRefreshing == true,
        removableMemberUserIds = removableMemberUserIds,
    )
}
