package com.vrcx.android.service

import com.vrcx.android.data.repository.AccountScope
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.FriendRepository
import com.vrcx.android.data.repository.GalleryRepository
import com.vrcx.android.data.repository.GroupRepository
import com.vrcx.android.data.repository.NotificationRepository
import com.vrcx.android.data.repository.PipelineSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PipelineStateResynchronizerTest {
    private val authRepository = mock<AuthRepository>()
    private val friendRepository = mock<FriendRepository>()
    private val notificationRepository = mock<NotificationRepository>()
    private val groupRepository = mock<GroupRepository>()
    private val galleryRepository = mock<GalleryRepository>()
    private val resynchronizer = PipelineStateResynchronizer(
        authRepository,
        friendRepository,
        notificationRepository,
        groupRepository,
        galleryRepository,
    )
    private val origin = AccountScope.Token("usr_test", 7)

    @Before
    fun setUp() {
        whenever(authRepository.pipelineSession()).thenReturn(PipelineSession("pipeline-token", origin))
    }

    @Test
    fun `core recovery checks the session before starting independent owners concurrently`() = runTest {
        val sessionChecked = CompletableDeferred<Unit>()
        val friendsStarted = CompletableDeferred<Unit>()
        val notificationsStarted = CompletableDeferred<Unit>()
        val groupsStarted = CompletableDeferred<Unit>()
        val releaseCore = CompletableDeferred<Unit>()
        whenever(authRepository.resynchronizePipelineState(origin)).doSuspendableAnswer {
            sessionChecked.complete(Unit)
            Unit
        }
        whenever(friendRepository.resynchronize(origin)).doSuspendableAnswer {
            assertTrue(sessionChecked.isCompleted)
            friendsStarted.complete(Unit)
            releaseCore.await()
        }
        whenever(notificationRepository.resynchronize(origin)).doSuspendableAnswer {
            assertTrue(sessionChecked.isCompleted)
            notificationsStarted.complete(Unit)
            releaseCore.await()
        }
        whenever(groupRepository.loadMyGroups(origin)).doSuspendableAnswer {
            assertTrue(sessionChecked.isCompleted)
            groupsStarted.complete(Unit)
            releaseCore.await()
        }

        val recovery = async(start = CoroutineStart.UNDISPATCHED) {
            resynchronizer.resynchronizeCore(origin)
        }
        runCurrent()

        assertTrue(friendsStarted.isCompleted)
        assertTrue(notificationsStarted.isCompleted)
        assertTrue(groupsStarted.isCompleted)
        releaseCore.complete(Unit)
        assertTrue(recovery.await())
        verify(galleryRepository, never()).resynchronize(origin)
    }

    @Test
    fun `a failed session check defers every core owner`() = runTest {
        whenever(authRepository.resynchronizePipelineState(origin))
            .thenThrow(IllegalStateException("session unavailable"))

        assertFalse(resynchronizer.resynchronizeCore(origin))

        verify(friendRepository, never()).resynchronize(origin)
        verify(notificationRepository, never()).resynchronize(origin)
        verify(groupRepository, never()).loadMyGroups(origin)
    }

    @Test
    fun `a session check that no longer owns the pipeline defers every core owner`() = runTest {
        val replacement = AccountScope.Token("usr_other", 8)
        whenever(authRepository.pipelineSession()).thenReturn(PipelineSession("other-token", replacement))

        assertFalse(resynchronizer.resynchronizeCore(origin))

        verify(friendRepository, never()).resynchronize(origin)
        verify(notificationRepository, never()).resynchronize(origin)
        verify(groupRepository, never()).loadMyGroups(origin)
    }

    @Test
    fun `one failed core owner does not prevent the remaining reconciliation`() = runTest {
        whenever(friendRepository.resynchronize(origin))
            .thenThrow(IllegalStateException("friends unavailable"))

        assertFalse(resynchronizer.resynchronizeCore(origin))

        verify(notificationRepository).resynchronize(origin)
        verify(groupRepository).loadMyGroups(origin)
    }

    @Test
    fun `gallery reconciliation is an explicit optional phase`() = runTest {
        assertTrue(resynchronizer.resynchronizeGallery(origin))

        verify(galleryRepository).resynchronize(origin)
        verify(authRepository, never()).resynchronizePipelineState(origin)
    }

    @Test
    fun `recovery retries with bounded backoff until core state is authoritative`() = runTest {
        var attempts = 0
        val delays = mutableListOf<Long>()

        val recovered = retryPipelineStateRecovery(
            isCurrent = { true },
            recoverCore = { ++attempts >= 4 },
            delayBeforeRetry = { delays += it },
        )

        assertTrue(recovered)
        assertEquals(4, attempts)
        assertEquals(listOf(1_000L, 2_000L, 4_000L), delays)
        assertEquals(30_000L, pipelineRecoveryRetryDelayMs(Int.MAX_VALUE))
    }

    @Test
    fun `recovery stops without another delay when its account becomes stale`() = runTest {
        var current = true
        var delayed = false

        val recovered = retryPipelineStateRecovery(
            isCurrent = { current },
            recoverCore = {
                current = false
                false
            },
            delayBeforeRetry = { delayed = true },
        )

        assertFalse(recovered)
        assertFalse(delayed)
    }

    @Test
    fun `recovery preserves genuine coroutine cancellation`() = runTest {
        var delayed = false
        var cancelled = false

        try {
            retryPipelineStateRecovery(
                isCurrent = { true },
                recoverCore = { throw CancellationException("service stopped") },
                delayBeforeRetry = { delayed = true },
            )
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertFalse(delayed)
    }
}
