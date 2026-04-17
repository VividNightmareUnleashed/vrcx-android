package com.vrcx.android.service

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BootReceiverTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val config = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @Test
    fun `onReceive returns immediately for non-boot intents without enqueueing`() {
        val receiver = BootReceiver()
        val before = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("boot-reconnect-worker")
            .get()
            .size

        receiver.onReceive(context, Intent(Intent.ACTION_PACKAGE_ADDED))

        val after = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("boot-reconnect-worker")
            .get()
            .size
        assertEquals(before, after)
    }

    @Test
    fun `onReceive enqueues BootReconnectWorker on BOOT_COMPLETED without doing IO`() {
        val receiver = BootReceiver()
        val start = System.nanoTime()
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        val durationMs = (System.nanoTime() - start) / 1_000_000

        // The receiver should never block on disk reads. Even on a slow CI host,
        // a no-IO enqueue completes in well under 100ms — anything higher means
        // we slipped a runBlocking back in.
        assert(durationMs < 200) { "BootReceiver.onReceive took ${durationMs}ms, expected < 200ms" }

        val workInfos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("boot-reconnect-worker")
            .get()
        assertEquals(1, workInfos.size)
        // Worker is enqueued (not yet run) since constraints are unmet in test.
        assert(workInfos.first().state in setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.SUCCEEDED, WorkInfo.State.RUNNING))
    }
}
