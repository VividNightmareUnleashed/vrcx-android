package com.vrcx.android.ui.screen.profile

import com.vrcx.android.data.repository.ProfilePreferenceActions
import com.vrcx.android.data.repository.UserActionPerformer

internal data class UserDetailMutationResult(
    val message: String,
    val note: String? = null,
    val memo: String? = null,
    val notifyEnabled: Boolean? = null,
    val refreshProfile: Boolean = false,
)

internal class UserDetailMutationRunner(
    private val userId: String,
    private val actionPerformer: UserActionPerformer,
    private val preferenceActions: ProfilePreferenceActions,
) {
    suspend fun execute(mutation: UserDetailMutation, state: UserDetailUiState): UserDetailMutationResult? =
        when (mutation) {
            UserDetailMutation.ToggleFavorite -> toggleFavorite(state.favoriteEntryId)
            is UserDetailMutation.SaveNote -> saveNote(mutation.text, state.user?.displayName.orEmpty())
            is UserDetailMutation.SaveMemo -> saveMemo(mutation.text)
            UserDetailMutation.ToggleNotify -> toggleNotify()
            is UserDetailMutation.Social -> performSocialAction(mutation.action)
        }

    private suspend fun toggleFavorite(entryId: String?): UserDetailMutationResult {
        if (entryId == null) {
            preferenceActions.addFriendFavorite(userId)
        } else {
            preferenceActions.deleteFavorite(entryId)
        }
        return UserDetailMutationResult(
            message = if (entryId == null) "Added to favorites" else "Removed from favorites",
        )
    }

    private suspend fun saveNote(text: String, displayName: String): UserDetailMutationResult? {
        if (!preferenceActions.saveNote(userId, displayName, text)) return null
        return UserDetailMutationResult(
            message = if (text.isBlank()) "Note cleared" else "Note saved",
            note = text,
        )
    }

    private suspend fun saveMemo(text: String): UserDetailMutationResult? {
        if (!preferenceActions.saveMemo(userId, text)) return null
        return UserDetailMutationResult(message = "Memo saved", memo = text)
    }

    private suspend fun toggleNotify(): UserDetailMutationResult {
        val enabled = preferenceActions.toggleNotify(userId)
        return UserDetailMutationResult(
            message = if (enabled) "Notifications enabled" else "Notifications disabled",
            notifyEnabled = enabled,
        )
    }

    private suspend fun performSocialAction(action: UserDetailSocialAction): UserDetailMutationResult {
        when (action) {
            UserDetailSocialAction.REQUEST_INVITE -> actionPerformer.requestInvite(userId)
            UserDetailSocialAction.SEND_INVITE -> actionPerformer.sendInvite(userId)
            UserDetailSocialAction.SEND_BOOP -> actionPerformer.sendBoop(userId)
            UserDetailSocialAction.SEND_FRIEND_REQUEST -> actionPerformer.sendFriendRequest(userId)
            UserDetailSocialAction.CANCEL_FRIEND_REQUEST -> actionPerformer.cancelFriendRequest(userId)
            UserDetailSocialAction.UNFRIEND -> actionPerformer.unfriend(userId)
            UserDetailSocialAction.BLOCK -> actionPerformer.block(userId)
            UserDetailSocialAction.MUTE -> actionPerformer.mute(userId)
            UserDetailSocialAction.HIDE_AVATAR -> actionPerformer.hideAvatar(userId)
            UserDetailSocialAction.SHOW_AVATAR -> actionPerformer.showAvatar(userId)
        }
        return UserDetailMutationResult(
            message = action.successMessage,
            refreshProfile = action.refreshProfile,
        )
    }
}

internal val UserDetailMutation.requiresSerialization: Boolean
    get() = this is UserDetailMutation.ToggleFavorite || this is UserDetailMutation.Social
