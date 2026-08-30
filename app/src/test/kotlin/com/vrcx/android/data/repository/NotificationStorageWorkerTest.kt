package com.vrcx.android.data.repository

import com.vrcx.android.data.api.model.VrcNotification
import com.vrcx.android.data.db.dao.NotificationDao
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NotificationStorageWorkerTest {
    private val notificationDao = mock<NotificationDao>()
    private val accountScope = AccountScope().apply { bind(OWNER_ID) }

    @Test
    fun `single delete is applied to both notification sources`() = runTest {
        val worker = worker()

        worker.delete(accountScope.current(), listOf("noty_1"))
        runCurrent()

        verify(notificationDao).deleteNotification(OWNER_ID, "noty_1")
        verify(notificationDao).deleteNotificationV2(OWNER_ID, "noty_1")
    }

    @Test
    fun `overflow persists accepted commands before requesting a resync`() = runTest {
        val operations = mutableListOf<String>()
        whenever(notificationDao.upsertNotifications(eq(OWNER_ID), any(), eq(MAX_CACHED)))
            .doSuspendableAnswer { operations += "persist" }
        val worker = worker(capacity = 1) { operations += "resync" }
        val token = accountScope.current()

        worker.upsert(token, notification("first"))
        worker.upsert(token, notification("dropped"))
        runCurrent()

        assertEquals(listOf("persist", "resync"), operations)
    }

    @Test
    fun `a failed current-account write requests repair`() = runTest {
        val resyncs = mutableListOf<AccountScope.Token>()
        whenever(notificationDao.upsertNotifications(eq(OWNER_ID), any(), eq(MAX_CACHED)))
            .thenThrow(IllegalStateException("write failed"))
        val worker = worker { resyncs += it }
        val token = accountScope.current()

        worker.upsert(token, notification("noty_1"))
        runCurrent()

        assertEquals(listOf(token), resyncs)
    }

    @Test
    fun `a failed stale-account write does not request repair`() = runTest {
        val resyncs = mutableListOf<AccountScope.Token>()
        whenever(notificationDao.upsertNotifications(eq(OWNER_ID), any(), eq(MAX_CACHED)))
            .thenThrow(IllegalStateException("write failed"))
        val worker = worker { resyncs += it }
        val staleToken = accountScope.current()

        worker.upsert(staleToken, notification("noty_1"))
        accountScope.invalidate()
        accountScope.bind("usr_new")
        runCurrent()

        assertEquals(emptyList<AccountScope.Token>(), resyncs)
    }

    @Test
    fun `stale recovery cannot displace the current account repair`() = runTest {
        val resyncs = mutableListOf<AccountScope.Token>()
        val worker = worker(capacity = 1) { resyncs += it }
        val staleToken = accountScope.current()
        worker.upsert(staleToken, notification("accepted"))

        accountScope.invalidate()
        accountScope.bind("usr_new")
        val currentToken = accountScope.current()
        worker.upsert(currentToken, notification("current-overflow"))
        worker.upsert(staleToken, notification("stale-overflow"))
        runCurrent()

        assertEquals(listOf(currentToken), resyncs)
    }

    @Test
    fun `current account repair waits for an active old account recovery`() = runTest {
        whenever(notificationDao.upsertNotifications(any(), any(), eq(MAX_CACHED)))
            .thenThrow(IllegalStateException("write failed"))
        val firstRecoveryStarted = CompletableDeferred<Unit>()
        val finishFirstRecovery = CompletableDeferred<Unit>()
        val resyncs = mutableListOf<AccountScope.Token>()
        val worker = worker { token ->
            resyncs += token
            if (resyncs.size == 1) {
                firstRecoveryStarted.complete(Unit)
                finishFirstRecovery.await()
            }
        }
        val oldToken = accountScope.current()

        worker.upsert(oldToken, notification("old-account-write"))
        runCurrent()
        firstRecoveryStarted.await()

        accountScope.invalidate()
        accountScope.bind("usr_new")
        val currentToken = accountScope.current()
        worker.upsert(currentToken, notification("current-account-write"))
        runCurrent()

        finishFirstRecovery.complete(Unit)
        runCurrent()

        assertEquals(listOf(oldToken, currentToken), resyncs)
    }

    @Test
    fun `a newer successful resync cancels an older delayed retry`() = runTest {
        var resyncCount = 0
        whenever(notificationDao.upsertNotifications(eq(OWNER_ID), any(), eq(MAX_CACHED)))
            .thenThrow(IllegalStateException("write failed"))
        val worker = worker { _ ->
            resyncCount++
            if (resyncCount == 1) error("resync failed")
        }
        val token = accountScope.current()

        worker.upsert(token, notification("noty_1"))
        runCurrent()
        worker.upsert(token, notification("noty_2"))
        runCurrent()

        assertEquals(2, resyncCount)
        advanceTimeBy(RESYNC_RETRY_BASE_DELAY_MS)
        runCurrent()
        assertEquals(2, resyncCount)
    }

    @Test
    fun `resync retries use exponential backoff`() = runTest {
        val attemptTimes = mutableListOf<Long>()
        whenever(notificationDao.upsertNotifications(eq(OWNER_ID), any(), eq(MAX_CACHED)))
            .thenThrow(IllegalStateException("write failed"))
        val worker = worker { _ ->
            attemptTimes += testScheduler.currentTime
            error("resync failed")
        }

        worker.upsert(accountScope.current(), notification("noty_1"))
        runCurrent()
        RESYNC_RETRY_DELAYS_MS.forEach { delayMs ->
            advanceTimeBy(delayMs)
            runCurrent()
        }

        assertEquals(EXPECTED_RESYNC_ATTEMPT_TIMES_MS, attemptTimes)
    }

    @Test
    fun `persistent resync failure stops at the retry limit`() = runTest {
        var resyncCount = 0
        whenever(notificationDao.upsertNotifications(eq(OWNER_ID), any(), eq(MAX_CACHED)))
            .thenThrow(IllegalStateException("write failed"))
        val worker = worker { _ ->
            resyncCount++
            error("resync failed")
        }

        worker.upsert(accountScope.current(), notification("noty_1"))
        runCurrent()
        RESYNC_RETRY_DELAYS_MS.forEach { delayMs ->
            advanceTimeBy(delayMs)
            runCurrent()
        }
        advanceTimeBy(TIME_AFTER_RETRY_LIMIT_MS)
        runCurrent()

        assertEquals(MAX_RESYNC_ATTEMPTS, resyncCount)
    }

    @Test
    fun `a new storage failure resets an exhausted retry budget`() = runTest {
        var resyncCount = 0
        whenever(notificationDao.upsertNotifications(eq(OWNER_ID), any(), eq(MAX_CACHED)))
            .thenThrow(IllegalStateException("write failed"))
        val worker = worker { _ ->
            resyncCount++
            error("resync failed")
        }
        val token = accountScope.current()

        worker.upsert(token, notification("noty_1"))
        runCurrent()
        RESYNC_RETRY_DELAYS_MS.forEach { delayMs ->
            advanceTimeBy(delayMs)
            runCurrent()
        }
        assertEquals(MAX_RESYNC_ATTEMPTS, resyncCount)

        worker.upsert(token, notification("noty_2"))
        runCurrent()
        assertEquals(MAX_RESYNC_ATTEMPTS + 1, resyncCount)
        advanceTimeBy(RESYNC_RETRY_BASE_DELAY_MS)
        runCurrent()
        assertEquals(MAX_RESYNC_ATTEMPTS + 2, resyncCount)
    }

    private fun kotlinx.coroutines.test.TestScope.worker(
        capacity: Int = 4,
        requestResync: suspend (AccountScope.Token) -> Unit = {},
    ) = NotificationStorageWorker(
        notificationDao = notificationDao,
        json = Json { ignoreUnknownKeys = true },
        accountScope = accountScope,
        config = NotificationStorageWorker.Config(
            scope = backgroundScope,
            capacity = capacity,
        ),
        maxCachedNotifications = MAX_CACHED,
        requestResync = requestResync,
    )

    private fun notification(id: String) = VrcNotification(id = id, createdAt = "2026-03-19T00:00:00Z")

    private companion object {
        const val OWNER_ID = "usr_me"
        const val MAX_CACHED = 5_000
        const val RESYNC_RETRY_BASE_DELAY_MS = 30_000L
        const val TIME_AFTER_RETRY_LIMIT_MS = 1_000_000L
        const val MAX_RESYNC_ATTEMPTS = 4
        val RESYNC_RETRY_DELAYS_MS = listOf(30_000L, 60_000L, 120_000L)
        val EXPECTED_RESYNC_ATTEMPT_TIMES_MS = listOf(0L, 30_000L, 90_000L, 210_000L)
    }
}
