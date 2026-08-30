package com.vrcx.android.data.repository

import com.vrcx.android.data.db.entity.FeedOnlineOfflineEntity
import com.vrcx.android.di.DispatcherModule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class FriendActivityRecorderTest {

    @Test
    fun `failed activity write releases its dedupe reservation for an immediate retry`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val feedRepository = mock<FeedRepository>()
        val accountScope = AccountScope()
        val recorder = FriendActivityRecorder(feedRepository, accountScope, dispatcher)
        accountScope.bind("usr_owner")
        val token = accountScope.current()
        var attempts = 0
        val retryFinished = CompletableDeferred<Unit>()
        whenever(feedRepository.getLatestOnlineOffline("usr_owner", "usr_target")).thenReturn(null)
        whenever(feedRepository.insertOnlineOffline(any()))
            .doSuspendableAnswer {
                attempts++
                if (attempts == 1) error("database unavailable")
                retryFinished.complete(Unit)
                Unit
            }

        recorder.recordOnlineOffline(token, "usr_target", "Target", "online", "wrld_a:1")
        runCurrent()
        assertEquals(1, attempts)

        recorder.recordOnlineOffline(token, "usr_target", "Target", "online", "wrld_a:1")
        runCurrent()

        assertEquals(2, attempts)
        assertTrue(retryFinished.isCompleted)
    }

    @Test
    fun `activity writes for one friend execute in submission order`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val feedRepository = mock<FeedRepository>()
        val accountScope = AccountScope()
        val recorder = FriendActivityRecorder(feedRepository, accountScope, dispatcher)
        accountScope.bind("usr_owner")
        val token = accountScope.current()
        val releaseFirstWrite = CompletableDeferred<Unit>()
        val phases = mutableListOf<String>()
        var lookupCount = 0
        whenever(feedRepository.getLatestOnlineOffline("usr_owner", "usr_target"))
            .doSuspendableAnswer {
                lookupCount++
                null
            }
        whenever(feedRepository.insertOnlineOffline(any()))
            .doSuspendableAnswer { invocation ->
                val type = invocation.getArgument<FeedOnlineOfflineEntity>(0).type
                phases += "start:$type"
                if (type == "online") releaseFirstWrite.await()
                phases += "finish:$type"
                Unit
            }

        recorder.recordOnlineOffline(token, "usr_target", "Target", "online", "wrld_a:1")
        recorder.recordOnlineOffline(token, "usr_target", "Target", "offline", "")
        runCurrent()

        assertEquals(1, lookupCount)
        assertEquals(listOf("start:online"), phases)

        releaseFirstWrite.complete(Unit)
        runCurrent()

        assertEquals(2, lookupCount)
        assertEquals(listOf("start:online", "finish:online", "start:offline", "finish:offline"), phases)
    }

    @Test
    fun `same-account dedupe reset does not cancel a pending activity write`() = runBlocking {
        withTimeout(10_000) {
            val feedRepository = mock<FeedRepository>()
            val accountScope = AccountScope()
            val recorder = FriendActivityRecorder(
                feedRepository = feedRepository,
                accountScope = accountScope,
                ioDispatcher = DispatcherModule.provideIoDispatcher(),
            )
            accountScope.bind("usr_owner")
            val token = accountScope.current()
            val lookupStarted = CompletableDeferred<Unit>()
            val releaseLookup = CompletableDeferred<Unit>()
            val writeFinished = CompletableDeferred<Unit>()
            whenever(feedRepository.getLatestOnlineOffline("usr_owner", "usr_target"))
                .doSuspendableAnswer {
                    lookupStarted.complete(Unit)
                    releaseLookup.await()
                    null
                }
            whenever(feedRepository.insertOnlineOffline(any()))
                .doSuspendableAnswer {
                    writeFinished.complete(Unit)
                    Unit
                }

            recorder.recordOnlineOffline(token, "usr_target", "Target", "online", "wrld_a:1")
            lookupStarted.await()

            recorder.resetDedupe(token)
            releaseLookup.complete(Unit)
            writeFinished.await()

            verify(feedRepository).insertOnlineOffline(any())
        }
    }

    @Test
    fun `account invalidation cancels a pending activity write`() = runBlocking {
        withTimeout(10_000) {
            val feedRepository = mock<FeedRepository>()
            val accountScope = AccountScope()
            val recorder = FriendActivityRecorder(
                feedRepository = feedRepository,
                accountScope = accountScope,
                ioDispatcher = DispatcherModule.provideIoDispatcher(),
            )
            accountScope.bind("usr_old")
            val token = accountScope.current()
            val lookupStarted = CompletableDeferred<Unit>()
            val lookupCancelled = CompletableDeferred<Unit>()
            whenever(feedRepository.getLatestOnlineOffline("usr_old", "usr_target"))
                .doSuspendableAnswer {
                    lookupStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        lookupCancelled.complete(Unit)
                    }
                }

            recorder.recordOnlineOffline(token, "usr_target", "Target", "online", "wrld_a:1")
            lookupStarted.await()

            accountScope.invalidate()
            lookupCancelled.await()

            verify(feedRepository, never()).insertOnlineOffline(any())
        }
    }
}
