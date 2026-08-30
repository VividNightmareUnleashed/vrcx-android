package com.vrcx.android.ui.screen.profile

internal sealed interface UserDetailUiAction {
    data object Reload : UserDetailUiAction

    data object ToggleFavorite : UserDetailUiAction

    data class SelectTab(val tab: UserDetailTab) : UserDetailUiAction

    data class SelectFavoriteWorldGroup(val tag: String) : UserDetailUiAction

    data object EditNote : UserDetailUiAction

    data object EditMemo : UserDetailUiAction

    data object ToggleNotify : UserDetailUiAction

    data object SendBoop : UserDetailUiAction

    data object SendFriendRequest : UserDetailUiAction

    data object CancelFriendRequest : UserDetailUiAction

    data object SendInvite : UserDetailUiAction

    data object RequestInvite : UserDetailUiAction

    data class RequestDestructiveConfirmation(val action: UserDestructiveAction) : UserDetailUiAction
}
