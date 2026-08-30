package com.vrcx.android.ui.screen.notifications

import com.vrcx.android.data.api.model.NotificationAction
import com.vrcx.android.data.repository.InviteMessageRepository
import com.vrcx.android.data.repository.InviteMessageTemplate
import com.vrcx.android.data.repository.InviteMessageType
import com.vrcx.android.data.repository.NotificationCategoryFilter
import com.vrcx.android.data.repository.NotificationRepository
import com.vrcx.android.data.repository.NotificationSource
import com.vrcx.android.data.repository.UnifiedNotification
import com.vrcx.android.ui.common.LoadState
import com.vrcx.android.ui.common.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()
    private val testDispatcher = mainDispatcherRule.dispatcher

    @Test
    fun `declineFriendRequest hides the V1 notification via the repository`() = runTest(testDispatcher) {
        val notification = friendRequestNotification("notif_1", NotificationSource.V1)
        val (vm, repo) = buildViewModel()

        vm.declineFriendRequest(notification)
        advanceUntilIdle()

        verify(repo).hide(notification)
    }

    @Test
    fun `dismissed invite dialog is not reopened by its template load`() = runTest(testDispatcher) {
        val inviteRepository = mock<InviteMessageRepository>()
        whenever(inviteRepository.getMessages(any())).thenReturn(emptyList())
        val repo = mock<NotificationRepository>().also {
            whenever(it.unifiedNotifications).thenReturn(MutableStateFlow(emptyList()))
        }
        val tested = NotificationsViewModel(repo, inviteRepository)
        val invite = friendRequestNotification(
            "invite_1",
            NotificationSource.V1,
        ).copy(type = "invite")

        tested.openInviteResponseDialog(invite)
        tested.dismissInviteResponseDialog()
        advanceUntilIdle()

        assertNull(tested.state.value.action.inviteResponseDialog)
    }

    @Test
    fun `template refresh is ignored while a response is being sent`() = runTest(testDispatcher) {
        val inviteRepository = mock<InviteMessageRepository>()
        whenever(inviteRepository.getMessages(any())).thenReturn(listOf(template))
        val repo = mock<NotificationRepository>().also {
            whenever(it.unifiedNotifications).thenReturn(MutableStateFlow(emptyList()))
        }
        val tested = NotificationsViewModel(repo, inviteRepository)
        val invite = friendRequestNotification(
            "invite_1",
            NotificationSource.V1,
        ).copy(type = "invite")

        tested.openInviteResponseDialog(invite)
        advanceUntilIdle()
        val requestId = tested.state.value.action.inviteResponseDialog!!.requestId

        tested.sendInviteResponse(requestId, template)
        tested.refreshInviteResponseDialog()
        advanceUntilIdle()

        assertNull(tested.state.value.action.inviteResponseDialog)
        verify(inviteRepository, times(1)).getMessages(any())
    }

    @Test
    fun `category criteria and the rows derived from them publish as one snapshot`() = runTest(testDispatcher) {
        val notifications = MutableStateFlow(
            listOf(
                friendRequestNotification("friend", NotificationSource.V1),
                friendRequestNotification(
                    "group",
                    NotificationSource.V1,
                ).copy(type = "group.announcement"),
            ),
        )
        val repo = mock<NotificationRepository>().also {
            whenever(it.unifiedNotifications).thenReturn(notifications)
        }
        val tested = NotificationsViewModel(repo, mock())
        tested.state.first { it.notifications.size == 2 }

        tested.toggleTypeFilter("friendRequest")
        val typeFiltered = tested.state.first { it.selectedTypes == setOf("friendRequest") }
        assertEquals(listOf("friend"), typeFiltered.notifications.map { it.id })

        notifications.value = listOf(
            friendRequestNotification(
                "group",
                NotificationSource.V1,
            ).copy(type = "group.announcement"),
        )
        val retainedFilter = tested.state.first {
            "friendRequest" in it.visibleTypes && it.notifications.isEmpty()
        }
        assertEquals(setOf("friendRequest"), retainedFilter.selectedTypes)

        tested.selectCategory(NotificationCategoryFilter.GROUP)
        val groupState = tested.state.first { it.selectedCategory == NotificationCategoryFilter.GROUP }
        assertEquals(emptySet<String>(), groupState.selectedTypes)
        assertEquals(listOf("group"), groupState.notifications.map { it.id })
    }

    @Test
    fun `rows arriving after an initial failure make the error consumable`() = runTest(testDispatcher) {
        val notifications = MutableStateFlow(emptyList<UnifiedNotification>())
        val repo = mock<NotificationRepository>().also {
            whenever(it.unifiedNotifications).thenReturn(notifications)
            whenever(it.restoreNotifications()).thenThrow(IllegalStateException("restore failed"))
            whenever(it.loadNotifications()).thenThrow(IllegalStateException("refresh failed"))
        }
        val tested = NotificationsViewModel(repo, mock())
        advanceUntilIdle()

        notifications.value = listOf(friendRequestNotification("friend", NotificationSource.V1))
        val stale = tested.state.first {
            (it.loadState as? LoadState.Loaded)?.staleError == "refresh failed"
        }
        assertEquals(listOf("friend"), stale.notifications.map { it.id })

        tested.consumeLoadError(stale.loadErrorId)
        val consumed = tested.state.first {
            (it.loadState as? LoadState.Loaded)?.staleError == null
        }
        assertNull((consumed.loadState as LoadState.Loaded).staleError)
    }

    @Test
    fun `empty cache and failed network remain a retryable initial failure`() = runTest(testDispatcher) {
        val repo = mock<NotificationRepository>().also {
            whenever(it.unifiedNotifications).thenReturn(MutableStateFlow(emptyList()))
            whenever(it.loadNotifications()).thenThrow(IllegalStateException("refresh failed"))
        }
        val tested = NotificationsViewModel(repo, mock())
        advanceUntilIdle()

        assertEquals(LoadState.Failed("refresh failed"), tested.state.value.loadState)
        assertTrue(tested.state.value.notifications.isEmpty())
    }

    @Test
    fun `consuming an action failure preserves a concurrent refresh failure`() = runTest(testDispatcher) {
        val notification = friendRequestNotification("friend", NotificationSource.V1)
        val repo = mock<NotificationRepository>().also {
            whenever(it.unifiedNotifications).thenReturn(MutableStateFlow(listOf(notification)))
            whenever(it.loadNotifications()).thenThrow(IllegalStateException("refresh failed"))
            whenever(it.hide(notification)).thenThrow(IllegalStateException("action failed"))
        }
        val tested = NotificationsViewModel(repo, mock())
        advanceUntilIdle()
        tested.declineFriendRequest(notification)
        advanceUntilIdle()

        val failed = tested.state.value
        assertEquals("action failed", failed.action.error)
        assertEquals("refresh failed", (failed.loadState as LoadState.Loaded).staleError)

        tested.consumeActionError(failed.action.errorId)

        val actionConsumed = tested.state.first { it.action.error == null }
        assertEquals("refresh failed", (actionConsumed.loadState as LoadState.Loaded).staleError)
        tested.consumeLoadError(actionConsumed.loadErrorId)
        val bothConsumed = tested.state.first {
            (it.loadState as? LoadState.Loaded)?.staleError == null
        }
        assertNull((bothConsumed.loadState as LoadState.Loaded).staleError)
    }

    @Test
    fun `an old action error acknowledgement cannot clear a newer equal error`() = runTest(testDispatcher) {
        val notification = friendRequestNotification("friend", NotificationSource.V1)
        val repo = mock<NotificationRepository>().also {
            whenever(it.unifiedNotifications).thenReturn(MutableStateFlow(listOf(notification)))
            whenever(it.hide(notification)).thenThrow(IllegalStateException("action failed"))
        }
        val tested = NotificationsViewModel(repo, mock())
        advanceUntilIdle()

        tested.declineFriendRequest(notification)
        advanceUntilIdle()
        val firstErrorId = tested.state.value.action.errorId
        tested.declineFriendRequest(notification)
        advanceUntilIdle()
        val secondErrorId = tested.state.value.action.errorId

        tested.consumeActionError(firstErrorId)

        assertTrue(secondErrorId > firstErrorId)
        assertEquals("action failed", tested.state.value.action.error)
        assertEquals(secondErrorId, tested.state.value.action.errorId)
    }

    @Test
    fun `an old template error acknowledgement cannot clear a newer equal error`() = runTest(testDispatcher) {
        val inviteRepository = mock<InviteMessageRepository>().also {
            whenever(it.getMessages(any())).thenThrow(IllegalStateException("template failed"))
        }
        val repo = mock<NotificationRepository>().also {
            whenever(it.unifiedNotifications).thenReturn(MutableStateFlow(emptyList()))
        }
        val tested = NotificationsViewModel(repo, inviteRepository)
        tested.openInviteResponseDialog(
            friendRequestNotification("invite_1", NotificationSource.V1).copy(type = "invite"),
        )
        advanceUntilIdle()
        val firstErrorId = tested.state.value.action.errorId

        tested.refreshInviteResponseDialog()
        advanceUntilIdle()
        val secondErrorId = tested.state.value.action.errorId
        tested.consumeActionError(firstErrorId)

        assertTrue(secondErrorId > firstErrorId)
        assertEquals("template failed", tested.state.value.action.error)
        assertEquals(secondErrorId, tested.state.value.action.errorId)
    }

    @Test
    fun `loading with repository rows exposes refresh progress`() = runTest(testDispatcher) {
        val releaseRefresh = CompletableDeferred<Unit>()
        val notifications = MutableStateFlow(
            listOf(friendRequestNotification("friend", NotificationSource.V1)),
        )
        val repo = mock<NotificationRepository>().also {
            whenever(it.unifiedNotifications).thenReturn(notifications)
            whenever(it.loadNotifications()).doSuspendableAnswer { releaseRefresh.await() }
        }
        val tested = NotificationsViewModel(repo, mock())
        runCurrent()

        assertTrue((tested.state.value.loadState as LoadState.Loaded).isRefreshing)
        releaseRefresh.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `invite response submission is serialized and exposes sending state`() = runTest(testDispatcher) {
        val sendStarted = CompletableDeferred<Unit>()
        val releaseSend = CompletableDeferred<Unit>()
        val inviteRepository = mock<InviteMessageRepository>().also {
            whenever(it.getMessages(any())).thenReturn(listOf(template))
        }
        val repo = mock<NotificationRepository>().also {
            whenever(it.unifiedNotifications).thenReturn(MutableStateFlow(emptyList()))
            whenever(it.sendInviteResponse(any(), any())).doSuspendableAnswer {
                sendStarted.complete(Unit)
                releaseSend.await()
            }
        }
        val tested = NotificationsViewModel(repo, inviteRepository)
        tested.openInviteResponseDialog(
            friendRequestNotification("invite_1", NotificationSource.V1).copy(type = "invite"),
        )
        advanceUntilIdle()
        val requestId = tested.state.value.action.inviteResponseDialog!!.requestId

        tested.sendInviteResponse(requestId, template)
        runCurrent()
        sendStarted.await()
        assertTrue(tested.state.value.action.inviteResponseDialog!!.isSending)

        tested.sendInviteResponse(requestId, template)
        runCurrent()
        verify(repo, times(1)).sendInviteResponse(any(), any())

        releaseSend.complete(Unit)
        advanceUntilIdle()

        verify(repo, times(1)).sendInviteResponse(any(), any())
        assertNull(tested.state.value.action.inviteResponseDialog)
    }

    @Test
    fun `an old response completion cannot close a newer invite dialog`() = runTest(testDispatcher) {
        val sendStarted = CompletableDeferred<Unit>()
        val releaseSend = CompletableDeferred<Unit>()
        val inviteRepository = mock<InviteMessageRepository>().also {
            whenever(it.getMessages(any())).thenReturn(listOf(template))
        }
        val repo = mock<NotificationRepository>().also {
            whenever(it.unifiedNotifications).thenReturn(MutableStateFlow(emptyList()))
            whenever(it.sendInviteResponse(any(), any())).doSuspendableAnswer {
                sendStarted.complete(Unit)
                releaseSend.await()
            }
        }
        val tested = NotificationsViewModel(repo, inviteRepository)
        tested.openInviteResponseDialog(
            friendRequestNotification("invite_1", NotificationSource.V1).copy(type = "invite"),
        )
        advanceUntilIdle()
        val oldRequestId = tested.state.value.action.inviteResponseDialog!!.requestId

        tested.sendInviteResponse(oldRequestId, template)
        runCurrent()
        sendStarted.await()
        tested.dismissInviteResponseDialog()
        tested.openInviteResponseDialog(
            friendRequestNotification("invite_2", NotificationSource.V1).copy(type = "invite"),
        )
        runCurrent()
        val newRequestId = tested.state.value.action.inviteResponseDialog!!.requestId

        releaseSend.complete(Unit)
        advanceUntilIdle()

        assertEquals(newRequestId, tested.state.value.action.inviteResponseDialog?.requestId)
    }

    @Test
    fun `an old response failure cannot report an error on a newer invite dialog`() = runTest(testDispatcher) {
        val sendStarted = CompletableDeferred<Unit>()
        val releaseSend = CompletableDeferred<Unit>()
        val inviteRepository = mock<InviteMessageRepository>().also {
            whenever(it.getMessages(any())).thenReturn(listOf(template))
        }
        val repo = mock<NotificationRepository>().also {
            whenever(it.unifiedNotifications).thenReturn(MutableStateFlow(emptyList()))
            whenever(it.sendInviteResponse(any(), any())).doSuspendableAnswer {
                sendStarted.complete(Unit)
                releaseSend.await()
                error("old request failed")
            }
        }
        val tested = NotificationsViewModel(repo, inviteRepository)
        tested.openInviteResponseDialog(
            friendRequestNotification("invite_1", NotificationSource.V1).copy(type = "invite"),
        )
        advanceUntilIdle()
        val oldRequestId = tested.state.value.action.inviteResponseDialog!!.requestId

        tested.sendInviteResponse(oldRequestId, template)
        runCurrent()
        sendStarted.await()
        tested.dismissInviteResponseDialog()
        tested.openInviteResponseDialog(
            friendRequestNotification("invite_2", NotificationSource.V1).copy(type = "invite"),
        )
        runCurrent()
        val newRequestId = tested.state.value.action.inviteResponseDialog!!.requestId

        releaseSend.complete(Unit)
        advanceUntilIdle()

        assertEquals(newRequestId, tested.state.value.action.inviteResponseDialog?.requestId)
        assertNull(tested.state.value.action.error)
    }

    private val template = InviteMessageTemplate(
        slot = 0,
        message = "On my way",
        updatedAt = "2026-04-16T00:00:00Z",
        messageType = InviteMessageType.RESPONSE,
    )

    private fun buildViewModel(): Pair<NotificationsViewModel, NotificationRepository> {
        val repo = mock<NotificationRepository>().also {
            whenever(it.unifiedNotifications).thenReturn(MutableStateFlow(emptyList()))
        }
        val inviteMessageRepository = mock<InviteMessageRepository>()
        val vm = NotificationsViewModel(repo, inviteMessageRepository)
        return vm to repo
    }

    private fun friendRequestNotification(id: String, source: NotificationSource): UnifiedNotification =
        UnifiedNotification(
            id = id,
            type = "friendRequest",
            senderUserId = "usr_sender",
            senderUsername = "Sender",
            message = "wants to be friends",
            title = "",
            createdAt = "2026-04-16T00:00:00Z",
            seen = false,
            source = source,
            responses = emptyList<NotificationAction>(),
        )
}
