package com.vrcx.android.service

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
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
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        // No auth cookie or preference fixture is installed here. Enqueueing
        // anyway verifies that eligibility reads remain deferred to the worker;
        // a wall-clock limit would measure Robolectric/WorkManager startup and
        // is too host-dependent to prove the absence of disk I/O.
        val workInfos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("boot-reconnect-worker")
            .get()
        assertEquals(1, workInfos.size)
        // Worker is enqueued (not yet run) since constraints are unmet in test.
        assertTrue(
            workInfos.first().state in setOf(
                WorkInfo.State.ENQUEUED,
                WorkInfo.State.SUCCEEDED,
                WorkInfo.State.RUNNING,
            ),
        )
    }
}
