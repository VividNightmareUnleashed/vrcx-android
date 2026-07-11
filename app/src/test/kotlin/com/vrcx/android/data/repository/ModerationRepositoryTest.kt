package com.vrcx.android.data.repository

import com.vrcx.android.data.api.PlayerModerationApi
import com.vrcx.android.data.api.model.PlayerModeration
import com.vrcx.android.data.api.model.UnPlayerModerationRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ModerationRepositoryTest {
    @Test
    fun `removal sends target user and moderation type`() = runBlocking {
        val api = mock<PlayerModerationApi>()
        whenever(api.unmoderatePlayer(any())).thenReturn(JsonObject(emptyMap()))
        val repository = ModerationRepository(api)
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
}
