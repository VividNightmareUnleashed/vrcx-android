package com.vrcx.android.ui.screen.profile

/** A social action that requires confirmation because it changes how two users can interact. */
internal sealed class UserDestructiveAction(
    val verb: String,
    val consequence: String,
    val socialAction: UserDetailSocialAction,
) {
    data object Block : UserDestructiveAction(
        verb = "Block",
        consequence = "They won't be able to interact with you in-game and will be removed from your friends list.",
        socialAction = UserDetailSocialAction.BLOCK,
    )

    data object Mute : UserDestructiveAction(
        verb = "Mute",
        consequence = "You won't hear them speak in any instance.",
        socialAction = UserDetailSocialAction.MUTE,
    )

    data object HideAvatar : UserDestructiveAction(
        verb = "Hide avatar",
        consequence = "You'll see a fallback avatar in their place.",
        socialAction = UserDetailSocialAction.HIDE_AVATAR,
    )

    data object ShowAvatar : UserDestructiveAction(
        verb = "Show avatar",
        consequence = "Their custom avatar will load again.",
        socialAction = UserDetailSocialAction.SHOW_AVATAR,
    )

    data object Unfriend : UserDestructiveAction(
        verb = "Unfriend",
        consequence = "You'll be removed from each other's friends lists.",
        socialAction = UserDetailSocialAction.UNFRIEND,
    )
}
