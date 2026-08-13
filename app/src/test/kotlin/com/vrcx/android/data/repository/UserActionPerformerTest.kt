package com.vrcx.android.data.repository

import com.vrcx.android.data.api.FriendApi
import com.vrcx.android.data.api.NotificationApi
import com.vrcx.android.data.api.PlayerModerationApi
import com.vrcx.android.data.api.model.PlayerModeration
import com.vrcx.android.data.api.model.PlayerModerationRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class UserActionPerformerTest {

    private val playerModerationApi = mock<PlayerModerationApi>()
    private val friendApi = mock<FriendApi>()
    private val notificationApi = mock<NotificationApi>()
    private val notificationRepository = mock<NotificationRepository>()
    private val userRepository = mock<UserRepository>()

    private val moderationRepository = ModerationRepository(playerModerationApi, AccountScope())

    private val performer = UserActionPerformer(
        userRepository = userRepository,
        friendApi = friendApi,
        notificationApi = notificationApi,
        notificationRepository = notificationRepository,
        moderationRepository = moderationRepository,
    )

    private val empty = JsonObject(emptyMap())

    @Before
    fun stubNonNullReturns() {
        runBlocking {
            whenever(friendApi.sendFriendRequest(any())).thenReturn(empty)
            whenever(friendApi.cancelFriendRequest(any())).thenReturn(empty)
            whenever(friendApi.deleteFriend(any())).thenReturn(empty)
            whenever(notificationApi.sendRequestInvite(any(), any())).thenReturn(empty)
            whenever(userRepository.sendBoop(any())).thenReturn(empty)
        }
    }

    @Test
    fun `moderation actions send their VRChat wire values`() = runBlocking {
        whenever(playerModerationApi.sendPlayerModeration(any()))
            .thenReturn(PlayerModeration(id = "pmod_1", targetUserId = "usr_target"))

        performer.block("usr_target")
        performer.mute("usr_target")
        performer.hideAvatar("usr_target")
        performer.showAvatar("usr_target")

        val request = argumentCaptor<PlayerModerationRequest>()
        verify(playerModerationApi, times(4)).sendPlayerModeration(request.capture())
        assertEquals(
            listOf("block", "mute", "hideAvatar", "showAvatar"),
            request.allValues.map { it.type },
        )
        assertEquals(List(4) { "usr_target" }, request.allValues.map { it.moderated })
    }

    @Test
    fun `moderation actions publish through the repository so the list stays fresh`() =
        runBlocking {
            val created = PlayerModeration(
                id = "pmod_1",
                targetUserId = "usr_target",
                type = "block",
            )
            whenever(playerModerationApi.sendPlayerModeration(any())).thenReturn(created)

            performer.block("usr_target")

            assertEquals(listOf(created), moderationRepository.moderations.value)
        }

    @Test
    fun `friend actions go straight to the friend api`() {
        runBlocking {
            performer.sendFriendRequest("usr_target")
            performer.cancelFriendRequest("usr_target")
            performer.unfriend("usr_target")

            verify(friendApi).sendFriendRequest("usr_target")
            verify(friendApi).cancelFriendRequest("usr_target")
            verify(friendApi).deleteFriend("usr_target")
        }
    }

    @Test
    fun `invite and boop actions route to their own surfaces`() {
        runBlocking {
            performer.requestInvite("usr_target")
            performer.sendInvite("usr_target")
            performer.sendBoop("usr_target")

            verify(notificationApi).sendRequestInvite(eq("usr_target"), any())
            verify(notificationRepository).sendInviteToUser("usr_target")
            verify(userRepository).sendBoop("usr_target")
        }
    }
}
