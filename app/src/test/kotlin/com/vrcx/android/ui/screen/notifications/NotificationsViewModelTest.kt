package com.vrcx.android.ui.screen.notifications

import com.vrcx.android.data.api.model.NotificationAction
import com.vrcx.android.data.repository.InviteMessageRepository
import com.vrcx.android.data.repository.NotificationRepository
import com.vrcx.android.data.repository.UnifiedNotification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `declineFriendRequest hides the V1 notification via the repository`() = runTest(testDispatcher) {
        val notification = friendRequestNotification("notif_1", isV2 = false)
        val (vm, repo) = buildViewModel()

        vm.declineFriendRequest(notification)
        advanceUntilIdle()

        verify(repo).hideUnified(eq("notif_1"), eq(false))
    }

    @Test
    fun `declineFriendRequest forwards the V2 flag for newer notification format`() = runTest(testDispatcher) {
        val notification = friendRequestNotification("notif_v2", isV2 = true)
        val (vm, repo) = buildViewModel()

        vm.declineFriendRequest(notification)
        advanceUntilIdle()

        verify(repo).hideUnified(eq("notif_v2"), eq(true))
    }

    private fun buildViewModel(): Pair<NotificationsViewModel, NotificationRepository> {
        val repo = mock<NotificationRepository>().also {
            whenever(it.unifiedNotifications).thenReturn(MutableStateFlow(emptyList()))
        }
        val inviteMessageRepository = mock<InviteMessageRepository>()
        val vm = NotificationsViewModel(repo, inviteMessageRepository)
        return vm to repo
    }

    private fun friendRequestNotification(id: String, isV2: Boolean): UnifiedNotification = UnifiedNotification(
        id = id,
        type = "friendRequest",
        senderUserId = "usr_sender",
        senderUsername = "Sender",
        message = "wants to be friends",
        title = "",
        createdAt = "2026-04-16T00:00:00Z",
        seen = false,
        isV2 = isV2,
        responses = emptyList<NotificationAction>(),
    )
}
