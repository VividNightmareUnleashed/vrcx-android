package com.vrcx.android.ui.screen.notifications

import com.vrcx.android.data.api.model.NotificationAction
import com.vrcx.android.data.repository.InviteMessageRepository
import com.vrcx.android.data.repository.InviteMessageTemplate
import com.vrcx.android.data.repository.InviteMessageType
import com.vrcx.android.data.repository.NotificationRepository
import com.vrcx.android.data.repository.NotificationSource
import com.vrcx.android.data.repository.UnifiedNotification
import com.vrcx.android.ui.common.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
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
        val invite = friendRequestNotification("invite_1", NotificationSource.V1).copy(type = "invite")

        tested.openInviteResponseDialog(invite)
        tested.dismissInviteResponseDialog()
        advanceUntilIdle()

        assertNull(tested.inviteResponseDialog.value)
    }

    @Test
    fun `a template load in flight when a response is sent cannot reopen the dialog`() = runTest(testDispatcher) {
        val inviteRepository = mock<InviteMessageRepository>()
        whenever(inviteRepository.getMessages(any())).thenReturn(listOf(template))
        val repo = mock<NotificationRepository>().also {
            whenever(it.unifiedNotifications).thenReturn(MutableStateFlow(emptyList()))
        }
        val tested = NotificationsViewModel(repo, inviteRepository)
        val invite = friendRequestNotification("invite_1", NotificationSource.V1).copy(type = "invite")

        tested.openInviteResponseDialog(invite)
        advanceUntilIdle()

        tested.sendInviteResponse(template)
        tested.refreshInviteResponseDialog()
        advanceUntilIdle()

        assertNull(tested.inviteResponseDialog.value)
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

    private fun friendRequestNotification(id: String, source: NotificationSource): UnifiedNotification = UnifiedNotification(
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
