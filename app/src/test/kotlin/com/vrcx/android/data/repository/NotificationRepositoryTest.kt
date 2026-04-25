package com.vrcx.android.data.repository

import com.vrcx.android.data.api.NotificationApi
import com.vrcx.android.data.api.model.CurrentUser
import com.vrcx.android.data.api.model.InviteRequest
import com.vrcx.android.data.api.model.InviteResponseRequest
import com.vrcx.android.data.api.model.NotificationAction
import com.vrcx.android.data.api.model.NotificationV2
import com.vrcx.android.data.api.model.NotificationResponse
import com.vrcx.android.data.api.model.VrcNotification
import com.vrcx.android.data.websocket.PipelineEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class NotificationRepositoryTest {
    private val notificationApi = mock<NotificationApi>()
    private val authRepository = mock<AuthRepository>()
    private val notificationDao = mock<com.vrcx.android.data.db.dao.NotificationDao>()
    private val repository = NotificationRepository(
        notificationApi = notificationApi,
        authRepository = authRepository,
        notificationDao = notificationDao,
        json = Json { ignoreUnknownKeys = true },
    )

    @Test
    fun `friend request primary action accepts request`() {
        runBlocking {
            repository.performPrimaryAction(
                UnifiedNotification(
                    id = "noty_1",
                    type = "friendRequest",
                    senderUserId = "usr_sender",
                    senderUsername = "Sender",
                    message = "",
                    title = "",
                    createdAt = "",
                    seen = false,
                    isV2 = false,
                    responses = emptyList(),
                )
            )

            verify(notificationApi).acceptFriendRequest("noty_1")
        }
    }

    @Test
    fun `request invite primary action sends invite using current location`() {
        runBlocking {
            whenever(authRepository.currentUser).thenReturn(
                CurrentUser(
                    id = "usr_me",
                    location = "wrld_123:instance_456~region(eu)",
                )
            )

            repository.performPrimaryAction(
                UnifiedNotification(
                    id = "noty_req",
                    type = "requestInvite",
                    senderUserId = "usr_sender",
                    senderUsername = "Sender",
                    message = "",
                    title = "",
                    createdAt = "",
                    seen = false,
                    isV2 = false,
                    responses = emptyList(),
                )
            )

            val payloadCaptor = argumentCaptor<InviteRequest>()
            verify(notificationApi).sendInvite(eq("usr_sender"), payloadCaptor.capture())
            verify(notificationApi).hideNotification("noty_req")
            val payload = payloadCaptor.firstValue
            assertEquals("wrld_123:instance_456~region(eu)", payload.instanceId)
            assertEquals(null, payload.messageSlot)
        }
    }

    @Test
    fun `v2 response uses response payload`() {
        runBlocking {
            repository.respondToNotification(
                UnifiedNotification(
                    id = "noty_v2",
                    type = "message",
                    senderUserId = "usr_sender",
                    senderUsername = "Sender",
                    message = "",
                    title = "",
                    createdAt = "",
                    seen = false,
                    isV2 = true,
                    responses = listOf(
                        NotificationAction(
                            type = "accept",
                            text = "Accept",
                            data = "group:grp_123",
                        )
                    )
                ),
                responseType = "accept",
            )

            verify(notificationApi).sendNotificationResponse(
                "noty_v2",
                NotificationResponse(
                    responseType = "accept",
                    responseData = "group:grp_123",
                )
            )
        }
    }

    @Test
    fun `invite response uses legacy endpoint and clears notification`() {
        runBlocking {
            repository.sendInviteResponse(
                notificationId = "noty_invite",
                responseSlot = 3,
            )

            val payloadCaptor = argumentCaptor<InviteResponseRequest>()
            verify(notificationApi).sendInviteResponse(eq("noty_invite"), payloadCaptor.capture())
            verify(notificationApi).hideNotification("noty_invite")
            assertEquals(3, payloadCaptor.firstValue.responseSlot)
        }
    }

    @Test
    fun `loadNotifications propagates fetch failures`() {
        runBlocking {
            whenever(authRepository.currentUser).thenReturn(
                CurrentUser(id = "usr_me", displayName = "Me"),
            )
            whenever(notificationApi.getNotifications(any(), any(), anyOrNull(), any()))
                .thenThrow(IllegalStateException("boom"))

            assertThrows(IllegalStateException::class.java) {
                runBlocking { repository.loadNotifications() }
            }
        }
    }

    @Test
    fun `send invite uses traveling destination when current user is traveling`() {
        runBlocking {
            whenever(authRepository.currentUser).thenReturn(
                CurrentUser(
                    id = "usr_me",
                    location = "traveling",
                    travelingToLocation = "wrld_dest:inst_dest~region(us)",
                )
            )

            repository.sendInviteToUser("usr_target")

            val payloadCaptor = argumentCaptor<InviteRequest>()
            verify(notificationApi).sendInvite(eq("usr_target"), payloadCaptor.capture())
            val payload = payloadCaptor.firstValue
            assertEquals("wrld_dest:inst_dest~region(us)", payload.instanceId)
            assertEquals(null, payload.messageSlot)
        }
    }

    @Test
    fun `send invite includes message slot when supplied`() {
        runBlocking {
            whenever(authRepository.currentUser).thenReturn(
                CurrentUser(
                    id = "usr_me",
                    location = "wrld_123:instance_456~region(eu)",
                )
            )

            repository.sendInviteToUser("usr_target", messageSlot = 7)

            val payloadCaptor = argumentCaptor<InviteRequest>()
            verify(notificationApi).sendInvite(eq("usr_target"), payloadCaptor.capture())
            assertEquals("wrld_123:instance_456~region(eu)", payloadCaptor.firstValue.instanceId)
            assertEquals(7, payloadCaptor.firstValue.messageSlot)
        }
    }

    @Test
    fun `reset runtime state clears in-memory notification state`() {
        runBlocking {
            whenever(authRepository.currentUser).thenReturn(CurrentUser(id = "usr_me"))
            whenever(notificationDao.getNotifications("usr_me", 5000)).thenReturn(
                listOf(
                    com.vrcx.android.data.db.entity.NotificationEntity(
                        id = "noty_1",
                        ownerUserId = "usr_me",
                        type = "friendRequest",
                        createdAt = "2026-03-19T00:00:00Z",
                    )
                )
            )
            whenever(notificationDao.getNotificationsV2("usr_me", 5000)).thenReturn(
                listOf(
                    com.vrcx.android.data.db.entity.NotificationV2Entity(
                        id = "noty_v2",
                        ownerUserId = "usr_me",
                        type = "invite",
                        createdAt = "2026-03-19T00:00:01Z",
                    )
                )
            )
            repository.restoreNotifications()
            repository.handleEvent(
                PipelineEvent.InstanceClosed(
                    buildJsonObject {
                        put("instanceLocation", "wrld_123:inst_456")
                    }
                )
            )

            repository.resetRuntimeState()

            assertEquals(emptyList<VrcNotification>(), repository.notifications.value)
            assertEquals(emptyList<NotificationV2>(), repository.notificationsV2.value)
            assertEquals(emptyList<UnifiedNotification>(), repository.localNotifications.value)
            assertEquals(0, repository.unseenCount.value)
            assertEquals(emptyList<UnifiedNotification>(), repository.unifiedNotifications.first())
        }
    }

    @Test
    fun `instance closed events become local notifications`() {
        runBlocking {
            repository.handleEvent(
                PipelineEvent.InstanceClosed(
                    buildJsonObject {
                        put("instanceLocation", "wrld_123:inst_456")
                    }
                )
            )

            val notifications = repository.unifiedNotifications.first()
            val notification = notifications.first()
            assertEquals("instance.closed", notification.type)
            assertEquals("Instance Closed", notification.title)
            assertEquals("wrld_123:inst_456", notification.message)
        }
    }

    @Test
    fun `dismissing local notifications does not call remote hide endpoints`() {
        runBlocking {
            repository.handleEvent(
                PipelineEvent.InstanceClosed(
                    buildJsonObject {
                        put("instanceLocation", "wrld_123:inst_456")
                    }
                )
            )
            val notification = repository.unifiedNotifications.first().first()

            repository.hideUnified(notification.id, isV2 = false)

            verify(notificationApi, never()).hideNotification(notification.id)
            verify(notificationApi, never()).hideNotificationV2(notification.id)
            org.junit.Assert.assertEquals(emptyList<UnifiedNotification>(), repository.localNotifications.value)
        }
    }
}
