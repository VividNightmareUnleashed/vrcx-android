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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.timeout
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NotificationRepositoryTest {
    private val notificationApi = mock<NotificationApi>()
    private val authRepository = mock<AuthRepository>()
    private val notificationDao = mock<NotificationDao>()
    private val accountScope = AccountScope()
    private val repository = NotificationRepository(
        notificationApi = notificationApi,
        authRepository = authRepository,
        notificationDao = notificationDao,
        json = Json { ignoreUnknownKeys = true },
        accountScope = accountScope,
        ioDispatcher = UnconfinedTestDispatcher(),
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
    fun `incremental refresh stops on first page that overlaps ids already held`(): Unit = runBlocking {
        stubCurrentUser("usr_me")
        whenever(notificationApi.getNotifications(any(), any(), anyOrNull(), any())).thenReturn(listOf(v1("known", 1)))
        whenever(notificationApi.getNotificationsV2(any(), any(), anyOrNull())).thenReturn(emptyList())
        repository.loadNotifications()

        val page = (100 downTo 2).map { v1("new_$it", it) } + v1("known", 1)
        whenever(notificationApi.getNotifications(any(), any(), anyOrNull(), any())).thenReturn(page)

        repository.loadNotifications()

        verify(notificationApi, times(2)).getNotifications(any(), any(), anyOrNull(), any())
        val persisted = argumentCaptor<List<NotificationEntity>>()
        verify(notificationDao).upsertNotifications(eq("usr_me"), persisted.capture(), eq(5000))
        assertEquals(100, persisted.firstValue.size)
        assertEquals(100, repository.unifiedNotifications.value.size)
    }

    @Test
    fun `first refresh after a cold-start restore drops entries the server no longer returns`(): Unit = runBlocking {
        stubCurrentUser("usr_me")
        whenever(notificationDao.getNotifications("usr_me", 5000)).thenReturn(
            listOf(
                NotificationEntity(id = "handled_elsewhere", ownerUserId = "usr_me", createdAt = timestamp(1)),
                NotificationEntity(id = "still_open", ownerUserId = "usr_me", createdAt = timestamp(2)),
            ),
        )
        whenever(notificationDao.getNotificationsV2("usr_me", 5000)).thenReturn(emptyList())
        repository.restoreNotifications()
        assertEquals(
            listOf("still_open", "handled_elsewhere"),
            repository.unifiedNotifications.value.map { it.id },
        )
        whenever(notificationApi.getNotifications(any(), any(), anyOrNull(), any()))
            .thenReturn(listOf(v1("still_open", 2)))
        whenever(notificationApi.getNotificationsV2(any(), any(), anyOrNull())).thenReturn(emptyList())

        repository.loadNotifications()

        assertEquals(listOf("still_open"), repository.unifiedNotifications.value.map { it.id })
        verify(notificationDao).synchronizeNotifications(eq("usr_me"), any(), eq(5000))
        verify(notificationDao, never()).upsertNotifications(any(), any(), any())
    }

    @Test
    fun `a notification arriving mid-resync survives the reconcile`(): Unit = runBlocking {
        stubCurrentUser("usr_me")
        whenever(notificationDao.getNotifications("usr_me", 5000)).thenReturn(
            listOf(NotificationEntity(id = "handled_elsewhere", ownerUserId = "usr_me", createdAt = timestamp(1))),
        )
        whenever(notificationDao.getNotificationsV2("usr_me", 5000)).thenReturn(emptyList())
        repository.restoreNotifications()
        whenever(notificationApi.getNotifications(any(), any(), anyOrNull(), any())).thenAnswer {
            repository.handleEvent(notificationEvent("arrived_during_fetch", 5))
            emptyList<VrcNotification>()
        }
        whenever(notificationApi.getNotificationsV2(any(), any(), anyOrNull())).thenReturn(emptyList())

        repository.loadNotifications()

        assertEquals(listOf("arrived_during_fetch"), repository.unifiedNotifications.value.map { it.id })
        val persisted = argumentCaptor<List<NotificationEntity>>()
        val writes = inOrder(notificationDao)
        writes.verify(notificationDao).upsertNotifications(eq("usr_me"), any(), eq(5000))
        writes.verify(notificationDao).synchronizeNotifications(eq("usr_me"), persisted.capture(), eq(5000))
        assertEquals(listOf("arrived_during_fetch"), persisted.firstValue.map { it.id })
    }

    @Test
    fun `clear during a refresh discards the in-flight pages`(): Unit = runBlocking {
        stubCurrentUser("usr_me")
        whenever(notificationApi.getNotifications(any(), any(), anyOrNull(), any())).thenAnswer {
            repository.handleEvent(PipelineEvent.ClearNotification)
            listOf(v1("fetched_before_clear", 1))
        }
        whenever(notificationApi.getNotificationsV2(any(), any(), anyOrNull())).thenReturn(emptyList())

        repository.loadNotifications()

        assertEquals(emptyList<UnifiedNotification>(), repository.unifiedNotifications.value)
        verify(notificationDao, never()).synchronizeNotifications(any(), any(), any())
        verify(notificationDao, never()).upsertNotifications(any(), any(), any())
    }

    @Test
    fun `clear during an action does not abandon the rest of that action`(): Unit = runBlocking {
        stubCurrentUser("usr_me")
        val notification = unified("noty_invite", NotificationSource.V1, type = "invite")
        whenever(notificationApi.sendInviteResponse(eq("noty_invite"), any())).thenAnswer {
            repository.handleEvent(PipelineEvent.ClearNotification)
            buildJsonObject {}
        }

        repository.sendInviteResponse(notification, responseSlot = 3)

        verify(notificationApi).hideNotification("noty_invite")
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
        stubCurrentUser("usr_old")
        accountScope.bind("usr_old")
        whenever(notificationApi.getNotifications(any(), any(), anyOrNull(), any())).thenAnswer {
            accountScope.invalidate()
            accountScope.bind("usr_new")
            listOf(v1("old_account", 1))
        }
        whenever(notificationApi.getNotificationsV2(any(), any(), anyOrNull())).thenReturn(emptyList())

        assertThrows(AccountChangedException::class.java) {
            runBlocking { repository.loadNotifications() }
        }

        assertEquals(emptyList<UnifiedNotification>(), repository.unifiedNotifications.value)
        verify(notificationDao, never()).upsertNotifications(any(), any(), any())
        verify(notificationDao, never()).upsertNotificationsV2(any(), any(), any())
    }

    @Test
    fun `old account persistence cannot mark the new inbox fully resynced`() = runTest {
        val oldPersistenceStarted = CompletableDeferred<Unit>()
        val releaseOldPersistence = CompletableDeferred<Unit>()
        stubCurrentUser("usr_old")
        whenever(notificationApi.getNotifications(any(), any(), anyOrNull(), any()))
            .thenReturn(listOf(v1("old_remote", 1)), listOf(v1("new_remote", 3)))
        whenever(notificationApi.getNotificationsV2(any(), any(), anyOrNull())).thenReturn(emptyList())
        whenever(notificationDao.synchronizeNotifications(eq("usr_old"), any(), eq(5000)))
            .doSuspendableAnswer {
                oldPersistenceStarted.complete(Unit)
                releaseOldPersistence.await()
            }

        val oldRefresh = async(start = CoroutineStart.UNDISPATCHED) {
            repository.loadNotifications()
        }
        oldPersistenceStarted.await()

        accountScope.invalidate()
        stubCurrentUser("usr_new")
        whenever(notificationDao.getNotifications("usr_new", 5000)).thenReturn(
            listOf(NotificationEntity(id = "new_cached", ownerUserId = "usr_new", createdAt = timestamp(2))),
        )
        whenever(notificationDao.getNotificationsV2("usr_new", 5000)).thenReturn(emptyList())
        repository.restoreNotifications()

        releaseOldPersistence.complete(Unit)
        oldRefresh.await()
        repository.loadNotifications()

        verify(notificationDao).synchronizeNotifications(eq("usr_new"), any(), eq(5000))
        verify(notificationDao, never()).upsertNotifications(eq("usr_new"), any(), eq(5000))
    }

    @Test
    fun `account switch prevents late action from removing new account notification`(): Unit = runBlocking {
        stubCurrentUser("usr_old")
        accountScope.bind("usr_old")
        repository.handleEvent(notificationEvent("shared_id", 1))
        whenever(notificationApi.acceptFriendRequest("shared_id")).thenAnswer {
            accountScope.invalidate()
            accountScope.bind("usr_new")
            repository.handleEvent(notificationEvent("shared_id", 2))
            buildJsonObject {}
        }

        repository.performPrimaryAction(
            unified("shared_id", source = NotificationSource.V1, type = "friendRequest"),
        )

        assertEquals(listOf("shared_id"), repository.unifiedNotifications.value.map { it.id })
        verify(notificationDao, never()).deleteNotification(any(), eq("shared_id"))
        verify(notificationDao, never()).deleteNotificationV2(any(), eq("shared_id"))
    }

    @Test
    fun `pipeline frames with an unexpected content shape are ignored instead of throwing`(): Unit = runBlocking {
        stubCurrentUser("usr_me")
        repository.handleEvent(notificationEvent("noty_1", 1))

        repository.handleEvent(PipelineEvent.SeeNotification(buildJsonObject { put("id", "noty_1") }))
        repository.handleEvent(PipelineEvent.HideNotification(buildJsonObject { put("id", "noty_1") }))
        repository.handleEvent(PipelineEvent.ResponseNotification(JsonPrimitive("noty_1")))
        repository.handleEvent(PipelineEvent.Notification(JsonPrimitive("noty_2")))
        repository.handleEvent(PipelineEvent.NotificationV2(JsonPrimitive("noty_3")))
        repository.handleEvent(PipelineEvent.NotificationV2Delete(JsonPrimitive("noty_1")))
        repository.handleEvent(PipelineEvent.NotificationV2Update(JsonPrimitive("noty_1")))

        assertEquals(listOf("noty_1"), repository.unifiedNotifications.value.map { it.id })
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
        assertEquals(
            listOf(NotificationSource.V2, NotificationSource.V1),
            repository.unifiedNotifications.value.map {
                it.source
            },
        )
    }

    @Test
    fun `a pipeline mutation that lands during restore is not overwritten by the cached snapshot`() = runTest {
        val cacheReadStarted = CompletableDeferred<Unit>()
        val releaseCacheRead = CompletableDeferred<Unit>()
        whenever(notificationDao.getNotifications("usr_me", 5000)).doSuspendableAnswer {
            cacheReadStarted.complete(Unit)
            releaseCacheRead.await()
            listOf(NotificationEntity(id = "cached", ownerUserId = "usr_me", createdAt = timestamp(1)))
        }
        whenever(notificationDao.getNotificationsV2("usr_me", 5000)).thenReturn(emptyList())

        val restore = async(start = CoroutineStart.UNDISPATCHED) { repository.restoreNotifications() }
        cacheReadStarted.await()
        repository.handleEvent(notificationEvent("live", 2))
        releaseCacheRead.complete(Unit)
        restore.await()

        assertEquals(listOf("live"), repository.unifiedNotifications.value.map { it.id })
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
    fun `new notification frames return the same typed domain values stored in the inbox`() {
        val v1Result = repository.handleEvent(
            PipelineEvent.Notification(
                buildJsonObject {
                    put("id", "noty_v1")
                    put("type", "friendRequest")
                    put("senderUserId", "usr_sender")
                    put("senderUsername", "Sender")
                    put("created_at", timestamp(1))
                },
            ),
        )
        val v2Result = repository.handleEvent(
            PipelineEvent.NotificationV2(
                buildJsonObject {
                    put("id", "noty_v2")
                    put("type", "event.announcement")
                    put("title", "Announcement")
                    put("message", "Message")
                    put("createdAt", timestamp(2))
                    put("updatedAt", timestamp(2))
                },
            ),
        )
        val localResult = repository.handleEvent(instanceClosedEvent())

        assertEquals(accountScope.current(), v1Result?.origin)
        assertEquals(NotificationSource.V1, v1Result?.value?.source)
        assertEquals(NotificationKind.FRIEND_REQUEST, v1Result?.value?.kind)
        assertEquals(NotificationSource.V2, v2Result?.value?.source)
        assertEquals("Announcement", v2Result?.value?.title)
        assertEquals(NotificationSource.LOCAL, localResult?.value?.source)
        assertEquals("wrld_123:inst_456", localResult?.value?.message)
        assertEquals(
            setOf(v1Result?.value, v2Result?.value, localResult?.value),
            repository.unifiedNotifications.value.toSet(),
        )
    }

    @Test
    fun `v2 update merges its patch into the current notification`() {
        repository.handleEvent(
            PipelineEvent.NotificationV2(
                buildJsonObject {
                    put("id", "noty_v2")
                    put("type", "event.announcement")
                    put("title", "Original")
                    put("message", "Kept message")
                    put("createdAt", timestamp(1))
                    put("updatedAt", timestamp(1))
                },
            ),
        )

        repository.handleEvent(
            PipelineEvent.NotificationV2Update(
                buildJsonObject {
                    put("id", "noty_v2")
                    put("updates", buildJsonObject { put("title", "Updated") })
                },
            ),
        )

        val updated = repository.unifiedNotifications.value.single()
        assertEquals("Updated", updated.title)
        assertEquals("Kept message", updated.message)
    }

    @Test
    fun `a queued notification from the previous account is ignored`() = runBlocking {
        val oldSocketOrigin = accountScope.current()
        accountScope.invalidate()
        stubCurrentUser("usr_new")

        val result = repository.handleEvent(notificationEvent("noty_old", 1), oldSocketOrigin)

        assertEquals(null, result)
        assertEquals(emptyList<UnifiedNotification>(), repository.unifiedNotifications.value)
        verify(notificationDao, never()).upsertNotifications(any(), any(), any())
    }

    @Test
    fun `storage overflow preserves accepted order then repairs from an authoritative resync`() = runTest {
        whenever(notificationApi.getNotifications(any(), any(), anyOrNull(), any())).thenReturn(
            listOf(v1("second", 2), v1("first", 1)),
        )
        whenever(notificationApi.getNotificationsV2(any(), any(), anyOrNull())).thenReturn(emptyList())
        val boundedRepository = NotificationRepository(
            notificationApi = notificationApi,
            authRepository = authRepository,
            notificationDao = notificationDao,
            json = Json { ignoreUnknownKeys = true },
            accountScope = accountScope,
            storageConfig = NotificationStorageWorker.Config(
                scope = backgroundScope,
                capacity = 1,
            ),
        )

        boundedRepository.handleEvent(notificationEvent("first", 1))
        boundedRepository.handleEvent(notificationEvent("second", 2))
        runCurrent()

        val writes = inOrder(notificationDao)
        writes.verify(notificationDao).upsertNotifications(eq("usr_me"), any(), eq(5000))
        writes.verify(notificationDao).synchronizeNotifications(eq("usr_me"), any(), eq(5000))
        verify(notificationDao, times(1)).upsertNotifications(eq("usr_me"), any(), eq(5000))
        val snapshot = argumentCaptor<List<NotificationEntity>>()
        verify(notificationDao).synchronizeNotifications(eq("usr_me"), snapshot.capture(), eq(5000))
        assertEquals(setOf("first", "second"), snapshot.firstValue.map { it.id }.toSet())
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
        accountScope.bind(id)
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

    private fun unified(id: String, source: NotificationSource, type: String = "message") = UnifiedNotification(
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
