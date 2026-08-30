package com.vrcx.android.ui.screen.groups

internal sealed interface GroupDetailUiAction {
    data object RetryGroup : GroupDetailUiAction
    data object JoinOrLeaveGroup : GroupDetailUiAction
    data object RetryMembers : GroupDetailUiAction
    data object LoadMoreMembers : GroupDetailUiAction
    data object RetryInstances : GroupDetailUiAction
    data object RetryPosts : GroupDetailUiAction
    data object LoadMorePosts : GroupDetailUiAction
    data class SelectTab(val tab: GroupTab) : GroupDetailUiAction
    data class OpenUser(val userId: String) : GroupDetailUiAction
    data class RequestMemberRemoval(val userId: String) : GroupDetailUiAction
}
