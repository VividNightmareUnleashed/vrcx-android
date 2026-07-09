package com.vrcx.android.data.repository

import com.vrcx.android.data.api.InviteMessageApi
import com.vrcx.android.data.api.model.CurrentUser
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class InviteMessageRepositoryTest {
    private val inviteMessageApi = mock<InviteMessageApi>()
    private val authRepository = mock<AuthRepository>()
    private val repository = InviteMessageRepository(
        inviteMessageApi = inviteMessageApi,
        authRepository = authRepository,
        json = Json { ignoreUnknownKeys = true },
    )

    @Test
    fun `getMessages parses saved templates for current user`() {
        runBlocking {
            whenever(authRepository.currentUser).thenReturn(
                CurrentUser(
                    id = "usr_me",
                )
            )
            whenever(inviteMessageApi.getInviteMessages("usr_me", InviteMessageType.RESPONSE.apiValue)).thenReturn(
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("slot", 2)
                            put("message", "Maybe later")
                            put("updatedAt", "2026-04-03T10:00:00Z")
                        }
                    )
                    add(
                        buildJsonObject {
                            put("slot", 1)
                            put("message", "Thanks for the invite")
                            put("updatedAt", "2026-04-03T09:00:00Z")
                        }
                    )
                }
            )

            val templates = repository.getMessages(InviteMessageType.RESPONSE)

            assertEquals(listOf(1, 2), templates.map { it.slot })
            assertEquals("Thanks for the invite", templates.first().message)
            assertEquals(InviteMessageType.RESPONSE, templates.first().messageType)
        }
    }
}
