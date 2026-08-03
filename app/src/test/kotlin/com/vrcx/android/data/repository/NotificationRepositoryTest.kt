package com.vrcx.android.data.repository

import com.vrcx.android.data.api.NotificationApi
import com.vrcx.android.data.api.model.CurrentUser
import com.vrcx.android.data.api.model.InviteRequest
import com.vrcx.android.data.api.model.InviteResponseRequest
import com.vrcx.android.data.api.model.NotificationAction
import com.vrcx.android.data.api.model.NotificationResponse
import com.vrcx.android.data.api.model.NotificationV2
import com.vrcx.android.data.api.model.VrcNotification
import com.vrcx.android.data.db.dao.NotificationDao
import com.vrcx.android.data.db.entity.NotificationEntity
import com.vrcx.android.data.db.entity.NotificationV2Entity
import com.vrcx.android.data.websocket.PipelineEvent
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito.timeout
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class NotificationRepositoryTest {
    private val notificationApi = mock<NotificationApi>()
    private val authRepository = mock<AuthRepository>()
    private val notificationDao = mock<NotificationDao>()
    private val repository = NotificationRepository(
        notificationApi = notificationApi,
        authRepository = authRepository,
        notificationDao = notificationDao,
        json = Json { ignoreUnknownKeys = true },
    )

    init {
        stubCurrentUser("usr_me")
    }

    @Test
    fun `friend request primary action accepts request`(): Unit = runBlocking {
        val notification = unified("noty_1", source = NotificationSource.V1, type = "friendRequest")

        repository.performPrimaryAction(notification)

        verify(notificationApi).acceptFriendRequest("noty_1")
    }

    @Test
    fun `request invite primary action sends invite using current location`(): Unit = runBlocking {
        whenever(authRepository.currentUser).thenReturn(
            CurrentUser(id = "usr_me", location = "wrld_123:instance_456~region(eu)"),
        )
        val notification = unified(
            id = "noty_req",
            source = NotificationSource.V1,
            type = "requestInvite",
        ).copy(senderUserId = "usr_sender")

        repository.performPrimaryAction(notification)

        val payload = argumentCaptor<InviteRequest>()
        verify(notificationApi).sendInvite(eq("usr_sender"), payload.capture())
        verify(notificationApi).hideNotification("noty_req")
        assertEquals("wrld_123:instance_456~region(eu)", payload.firstValue.instanceId)
        assertEquals(null, payload.firstValue.messageSlot)
    }

    @Test
    fun `v2 response uses response payload`(): Unit = runBlocking {
        val action = NotificationAction(type = "accept", text = "Accept", data = "group:grp_123")
        val notification = unified("noty_v2", NotificationSource.V2).copy(responses = listOf(action))

        repository.respondToNotification(notification, responseType = "accept")

        verify(notificationApi).sendNotificationResponse(
            "noty_v2",
            NotificationResponse(responseType = "accept", responseData = "group:grp_123"),
        )
    }

    @Test
    fun `invite response uses legacy endpoint and clears notification`(): Unit = runBlocking {
        val notification = unified("noty_invite", NotificationSource.V1, type = "invite")

        repository.sendInviteResponse(notification, responseSlot = 3)

        val payload = argumentCaptor<InviteResponseRequest>()
        verify(notificationApi).sendInviteResponse(eq("noty_invite"), payload.capture())
        verify(notificationApi).hideNotification("noty_invite")
        assertEquals(3, payload.firstValue.responseSlot)
    }

    @Test
    fun `incremental refresh stops on first page that overlaps restored ids`(): Unit = runBlocking {
        stubCurrentUser("usr_me")
        whenever(notificationDao.getNotifications("usr_me", 5000)).thenReturn(
            listOf(NotificationEntity(id = "known", ownerUserId = "usr_me", createdAt = timestamp(1))),
        )
        whenever(notificationDao.getNotificationsV2("usr_me", 5000)).thenReturn(emptyList())
        repository.restoreNotifications()
        val page = (100 downTo 2).map { v1("new_$it", it) } + v1("known", 1)
        whenever(notificationApi.getNotifications(any(), any(), anyOrNull(), any())).thenReturn(page)
        whenever(notificationApi.getNotificationsV2(any(), any(), anyOrNull())).thenReturn(emptyList())

        repository.loadNotifications()

        verify(notificationApi, times(1)).getNotifications(any(), any(), anyOrNull(), any())
        val persisted = argumentCaptor<List<NotificationEntity>>()
        verify(notificationDao).upsertNotifications(eq("usr_me"), persisted.capture(), eq(5000))
        assertEquals(100, persisted.firstValue.size)
        assertEquals(100, repository.unifiedNotifications.value.size)
    }

    @Test
    fun `loadNotifications propagates fetch failures`(): Unit = runBlocking {
        stubCurrentUser("usr_me")
        whenever(notificationApi.getNotifications(any(), any(), anyOrNull(), any()))
            .thenThrow(IllegalStateException("boom"))
        whenever(notificationApi.getNotificationsV2(any(), any(), anyOrNull())).thenReturn(emptyList())

        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.loadNotifications() }
        }
    }

    @Test
    fun `account switch prevents late refresh from publishing or persisting old data`(): Unit = runBlocking {
        var currentUser = CurrentUser(id = "usr_old")
        whenever(authRepository.currentUser).thenAnswer { currentUser }
        whenever(notificationApi.getNotifications(any(), any(), anyOrNull(), any())).thenAnswer {
            currentUser = CurrentUser(id = "usr_new")
            repository.clearRuntimeState()
            listOf(v1("old_account", 1))
        }
        whenever(notificationApi.getNotificationsV2(any(), any(), anyOrNull())).thenReturn(emptyList())

        repository.loadNotifications()

        assertEquals(emptyList<UnifiedNotification>(), repository.unifiedNotifications.value)
        verify(notificationDao, never()).upsertNotifications(any(), any(), any())
        verify(notificationDao, never()).upsertNotificationsV2(any(), any(), any())
    }

    @Test
    fun `account switch prevents late action from removing new account notification`(): Unit = runBlocking {
        var currentUser = CurrentUser(id = "usr_old")
        whenever(authRepository.currentUser).thenAnswer { currentUser }
        repository.handleEvent(notificationEvent("shared_id", 1))
        whenever(notificationApi.acceptFriendRequest("shared_id")).thenAnswer {
            currentUser = CurrentUser(id = "usr_new")
            repository.clearRuntimeState()
            repository.handleEvent(notificationEvent("shared_id", 2))
            buildJsonObject {}
        }

        repository.performPrimaryAction(
            unified("shared_id", source = NotificationSource.V1, type = "friendRequest"),
        )

        assertEquals(listOf("shared_id"), repository.unifiedNotifications.value.map { it.id })
        verify(notificationDao, timeout(1_000)).deleteNotification("usr_old", "shared_id")
    }

    @Test
    fun `hide routes by closed source and local stays offline`(): Unit = runBlocking {
        val v1 = unified("v1", NotificationSource.V1)
        val v2 = unified("v2", NotificationSource.V2)
        repository.handleEvent(instanceClosedEvent())
        val local = repository.unifiedNotifications.value.single()
        assertEquals(NotificationSource.LOCAL, local.source)

        repository.hide(v1)
        repository.hide(v2)
        repository.hide(local)

        verify(notificationApi).hideNotification("v1")
        verify(notificationApi).hideNotificationV2("v2")
        verify(notificationApi, never()).hideNotification(local.id)
        verify(notificationApi, never()).hideNotificationV2(local.id)
        assertEquals(emptyList<UnifiedNotification>(), repository.unifiedNotifications.value)
    }

    @Test
    fun `restore publishes typed sources in timestamp order`(): Unit = runBlocking {
        stubCurrentUser("usr_me")
        whenever(notificationDao.getNotifications("usr_me", 5000)).thenReturn(
            listOf(NotificationEntity(id = "v1", ownerUserId = "usr_me", createdAt = timestamp(1))),
        )
        whenever(notificationDao.getNotificationsV2("usr_me", 5000)).thenReturn(
            listOf(NotificationV2Entity(id = "v2", ownerUserId = "usr_me", createdAt = timestamp(2))),
        )

        repository.restoreNotifications()

        assertEquals(listOf("v2", "v1"), repository.unifiedNotifications.value.map { it.id })
        assertEquals(listOf(NotificationSource.V2, NotificationSource.V1), repository.unifiedNotifications.value.map { it.source })
    }

    @Test
    fun `clear event clears current owner memory and storage`(): Unit = runBlocking {
        stubCurrentUser("usr_me")
        repository.handleEvent(notificationEvent("noty_1", 1))
        assertEquals(1, repository.unifiedNotifications.value.size)

        repository.handleEvent(PipelineEvent.ClearNotification)

        assertEquals(emptyList<UnifiedNotification>(), repository.unifiedNotifications.value)
        verify(notificationDao, timeout(1_000)).deleteNotificationsForUser("usr_me")
        verify(notificationDao, timeout(1_000)).deleteNotificationsV2ForUser("usr_me")
    }

    @Test
    fun `send invite uses traveling destination and optional message slot`(): Unit = runBlocking {
        whenever(authRepository.currentUser).thenReturn(
            CurrentUser(
                id = "usr_me",
                location = "traveling",
                travelingToLocation = "wrld_dest:inst_dest~region(us)",
            ),
        )

        repository.sendInviteToUser("usr_target", messageSlot = 7)

        val payload = argumentCaptor<InviteRequest>()
        verify(notificationApi).sendInvite(eq("usr_target"), payload.capture())
        assertEquals("wrld_dest:inst_dest~region(us)", payload.firstValue.instanceId)
        assertEquals(7, payload.firstValue.messageSlot)
    }

    private fun stubCurrentUser(id: String) {
        whenever(authRepository.currentUser).thenReturn(CurrentUser(id = id, displayName = "Me"))
    }

    private fun instanceClosedEvent() = PipelineEvent.InstanceClosed(
        buildJsonObject { put("instanceLocation", "wrld_123:inst_456") },
    )

    private fun notificationEvent(id: String, second: Int) = PipelineEvent.Notification(
        buildJsonObject {
            put("id", id)
            put("created_at", timestamp(second))
        },
    )

    private fun unified(
        id: String,
        source: NotificationSource,
        type: String = "message",
    ) = UnifiedNotification(
        id = id,
        type = type,
        senderUserId = "usr_sender",
        senderUsername = "Sender",
        message = "",
        title = "",
        createdAt = timestamp(1),
        seen = false,
        source = source,
        responses = emptyList(),
    )

    private fun v1(id: String, second: Int) = VrcNotification(
        id = id,
        createdAt = timestamp(second),
        type = "message",
    )

    private fun v2(id: String, second: Int) = NotificationV2(
        id = id,
        createdAt = timestamp(second),
        updatedAt = timestamp(second),
        type = "message",
        version = 1,
    )

    private fun timestamp(second: Int): String = Instant.parse("2026-03-19T00:00:00Z")
        .plusSeconds(second.toLong())
        .toString()
}
