package com.vrcx.android.ui.screen.profile

internal sealed interface UserDetailIntent {
    data object Reload : UserDetailIntent

    data class SelectTab(val tab: UserDetailTab) : UserDetailIntent

    data class SelectFavoriteWorldGroup(val tag: String) : UserDetailIntent

    data class Mutate(val mutation: UserDetailMutation) : UserDetailIntent

    data object ClearMessage : UserDetailIntent
}

internal sealed interface UserDetailMutation {
    data object ToggleFavorite : UserDetailMutation

    data class SaveNote(val text: String) : UserDetailMutation

    data class SaveMemo(val text: String) : UserDetailMutation

    data object ToggleNotify : UserDetailMutation

    data class Social(val action: UserDetailSocialAction) : UserDetailMutation
}

internal enum class UserDetailSocialAction(
    internal val successMessage: String,
    internal val refreshProfile: Boolean = false,
) {
    REQUEST_INVITE("Invite requested"),
    SEND_INVITE("Invite sent"),
    SEND_BOOP("Boop sent"),
    SEND_FRIEND_REQUEST("Friend request sent", refreshProfile = true),
    CANCEL_FRIEND_REQUEST("Friend request cancelled", refreshProfile = true),
    UNFRIEND("Unfriended", refreshProfile = true),
    BLOCK("User blocked"),
    MUTE("User muted"),
    HIDE_AVATAR("Avatar hidden"),
    SHOW_AVATAR("Avatar shown"),
}
