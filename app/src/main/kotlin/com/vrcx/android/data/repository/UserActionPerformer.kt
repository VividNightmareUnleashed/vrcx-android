package com.vrcx.android.data.repository

import com.vrcx.android.data.api.FriendApi
import com.vrcx.android.data.api.NotificationApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Performs the profile screen's write actions — the one-shot things you do
 * *to* another user from their profile.
 *
 * Split out of [UserDetailRepository] because it is the one place those writes
 * happen and it needs collaborators nothing else there uses — the friend,
 * notification and moderation surfaces. Keeping it separate is what stops the
 * profile aggregator from having to know about every write endpoint just to
 * load a user's tabs.
 *
 * Friend writes go straight to [FriendApi] on purpose: [FriendRepository]
 * self-heals its list from pipeline events, so an explicit refresh would be
 * redundant. Moderations have no pipeline event, which is why they route
 * through [ModerationRepository] instead.
 */
@Singleton
class UserActionPerformer @Inject constructor(
    private val userRepository: UserRepository,
    private val friendApi: FriendApi,
    private val notificationApi: NotificationApi,
    private val notificationRepository: NotificationRepository,
    private val moderationRepository: ModerationRepository,
) {
    suspend fun requestInvite(userId: String) {
        notificationApi.sendRequestInvite(userId)
    }

    suspend fun sendInvite(userId: String) = notificationRepository.sendInviteToUser(userId)

    suspend fun sendBoop(userId: String) {
        userRepository.sendBoop(userId)
    }

    suspend fun sendFriendRequest(userId: String) {
        friendApi.sendFriendRequest(userId)
    }

    suspend fun cancelFriendRequest(userId: String) {
        friendApi.cancelFriendRequest(userId)
    }

    suspend fun unfriend(userId: String) {
        friendApi.deleteFriend(userId)
    }

    suspend fun block(userId: String) = moderationRepository.moderate(userId, "block")

    suspend fun mute(userId: String) = moderationRepository.moderate(userId, "mute")

    suspend fun hideAvatar(userId: String) = moderationRepository.moderate(userId, "hideAvatar")

    suspend fun showAvatar(userId: String) = moderationRepository.moderate(userId, "showAvatar")
}
