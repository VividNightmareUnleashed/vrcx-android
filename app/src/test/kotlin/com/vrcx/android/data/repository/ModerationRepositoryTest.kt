package com.vrcx.android.data.repository

import com.vrcx.android.data.api.PlayerModerationApi
import com.vrcx.android.data.api.model.PlayerModeration
import com.vrcx.android.data.api.model.PlayerModerationRequest
import com.vrcx.android.data.api.model.UnPlayerModerationRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ModerationRepositoryTest {
    @Test
    fun `removal sends target user and moderation type`() = runBlocking {
        val api = mock<PlayerModerationApi>()
        whenever(api.unmoderatePlayer(any())).thenReturn(JsonObject(emptyMap()))
        val accountScope = AccountScope()
        val repository = ModerationRepository(api, accountScope)
        val moderation = PlayerModeration(
            id = "pmod_1",
            targetUserId = "usr_target",
            targetDisplayName = "Target",
            type = "block",
        )
        repository.deleteModeration(moderation)
        val request = argumentCaptor<UnPlayerModerationRequest>()
        verify(api).unmoderatePlayer(request.capture())
        assertEquals("usr_target", request.firstValue.moderated)
        assertEquals("block", request.firstValue.type)
    }

    @Test
    fun `moderate publishes the created row without refetching the list`() = runBlocking {
        val api = mock<PlayerModerationApi>()
        val created = PlayerModeration(
            id = "pmod_1",
            targetUserId = "usr_target",
            targetDisplayName = "Target",
            type = "block",
        )
        whenever(api.sendPlayerModeration(any())).thenReturn(created)
        val accountScope = AccountScope()
        val repository = ModerationRepository(api, accountScope)

        repository.moderate("usr_target", "block")

        assertEquals(listOf(created), repository.moderations.value)
        verify(api, never()).getPlayerModerations()
        val request = argumentCaptor<PlayerModerationRequest>()
        verify(api).sendPlayerModeration(request.capture())
        assertEquals("usr_target", request.firstValue.moderated)
        assertEquals("block", request.firstValue.type)
    }

    @Test
    fun `re-moderating the same user does not duplicate the row`() = runBlocking {
        val api = mock<PlayerModerationApi>()
        val created = PlayerModeration(
            id = "pmod_1",
            targetUserId = "usr_target",
            type = "block",
        )
        whenever(api.sendPlayerModeration(any())).thenReturn(created)
        val accountScope = AccountScope()
        val repository = ModerationRepository(api, accountScope)

        repository.moderate("usr_target", "block")
        repository.moderate("usr_target", "block")

        assertEquals(listOf(created), repository.moderations.value)
    }

    @Test
    fun `moderate ignores the result when runtime state is cleared mid-request`() = runBlocking {
        val api = mock<PlayerModerationApi>()
        val accountScope = AccountScope()
        val repository = ModerationRepository(api, accountScope)
        whenever(api.sendPlayerModeration(any())).thenAnswer {
            accountScope.invalidate()
            PlayerModeration(id = "pmod_1", targetUserId = "usr_target", type = "block")
        }

        repository.moderate("usr_target", "block")

        assertEquals(emptyList<PlayerModeration>(), repository.moderations.value)
    }
}
