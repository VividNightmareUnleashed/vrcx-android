package io.github.vrcxandroid.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceGovernorTest {
    /** A fake service and main-thread clock. */
    private class Fake : ServiceGovernor.Control {
        override var backgroundMode = true
        override var hasPage = true
        override var isRunning = false
        var starts = 0
        var stops = 0
        var now = 0L
        val tasks = mutableListOf<Pair<Long, Runnable>>()

        override fun start() {
            starts++
            isRunning = true
        }

        override fun stop() {
            stops++
            isRunning = false
        }

        override fun postDelayed(task: Runnable, delayMs: Long) {
            tasks += (now + delayMs) to task
        }

        override fun cancel(task: Runnable) {
            tasks.removeAll { it.second === task }
        }

        fun advance(ms: Long) {
            now += ms
            while (true) {
                val due = tasks.filter { it.first <= now }.minByOrNull { it.first } ?: break
                tasks.remove(due)
                due.second.run()
            }
        }
    }

    private val fake = Fake()
    private val governor = ServiceGovernor(fake, stopGraceMs = 1_000, bootHoldMs = 5_000)

    @Test
    fun theLoginPageKeepsNoServiceRunning() {
        governor.setVisible(true)
        governor.setVisible(false)
        fake.advance(10_000)
        assertEquals(0, fake.starts)
        assertFalse(fake.isRunning)
    }

    @Test
    fun aSessionStartsTheServiceWhileVisibleAndLogoutStopsItAtOnce() {
        governor.setVisible(true)
        governor.setSessionActive(true)
        assertTrue(fake.isRunning)
        governor.setSessionActive(false)
        assertFalse(fake.isRunning)
        assertEquals(1, fake.stops)
    }

    @Test
    fun aConnectedCompanionKeepsTheServiceWithoutASession() {
        governor.setVisible(true)
        governor.setCompanionConnected(true)
        assertTrue(fake.isRunning)
        governor.setVisible(false)
        fake.advance(10_000)
        assertTrue(fake.isRunning)
    }

    @Test
    fun whileHiddenTheServiceStopsOnlyAfterTheGracePeriod() {
        governor.setVisible(true)
        governor.setSessionActive(true)
        governor.setVisible(false)
        // a page reload reports the session again after auto-login
        governor.setSessionActive(false)
        fake.advance(500)
        assertTrue(fake.isRunning)
        governor.setSessionActive(true)
        fake.advance(10_000)
        assertTrue(fake.isRunning)
        assertEquals(0, fake.stops)
        // logged out for good
        governor.setSessionActive(false)
        fake.advance(999)
        assertTrue(fake.isRunning)
        fake.advance(1)
        assertFalse(fake.isRunning)
    }

    @Test
    fun aCompanionThatGoesAwayStopsTheServiceAfterTheGracePeriod() {
        governor.setVisible(true)
        governor.setCompanionConnected(true)
        governor.setVisible(false)
        governor.setCompanionConnected(false)
        assertTrue(fake.isRunning)
        fake.advance(1_000)
        assertFalse(fake.isRunning)
    }

    @Test
    fun comingBackToTheAppStopsAnUnneededServiceAtOnce() {
        governor.setVisible(true)
        governor.setSessionActive(true)
        governor.setVisible(false)
        governor.setSessionActive(false)
        governor.setVisible(true)
        assertFalse(fake.isRunning)
        assertTrue(fake.tasks.isEmpty())
    }

    @Test
    fun backgroundModeOffStopsTheServiceAtOnceEvenWhileHidden() {
        governor.setVisible(true)
        governor.setSessionActive(true)
        governor.setVisible(false)
        fake.backgroundMode = false
        governor.update()
        assertFalse(fake.isRunning)
        fake.backgroundMode = true
        governor.update()
        // hidden: a start is attempted (Android 12+ may refuse it; the fake accepts)
        assertTrue(fake.isRunning)
    }

    @Test
    fun noPageMeansNoService() {
        fake.hasPage = false
        governor.setVisible(true)
        governor.setSessionActive(true)
        assertFalse(fake.isRunning)
    }

    @Test
    fun startOnBootHoldsTheServiceUntilThePageLogsIn() {
        governor.holdForBoot()
        assertTrue(fake.isRunning)
        // the page reports "logged out" before auto-login has run: the hold stays
        governor.setSessionActive(false)
        fake.advance(4_000)
        assertTrue(fake.isRunning)
        governor.setSessionActive(true)
        assertFalse(governor.bootHold)
        fake.advance(60_000)
        assertTrue(fake.isRunning)
    }

    @Test
    fun startOnBootWithoutASessionEndsAfterTheHoldAndTheGracePeriod() {
        governor.holdForBoot()
        fake.advance(5_000)
        assertFalse(governor.bootHold)
        assertTrue(fake.isRunning)
        fake.advance(1_000)
        assertFalse(fake.isRunning)
    }

    @Test
    fun theBootReceiverIsOnlyEnabledWithStartOnBootAndBackgroundMode() {
        assertTrue(BootReceiver.shouldBeEnabled(startOnBoot = true, backgroundMode = true))
        assertFalse(BootReceiver.shouldBeEnabled(startOnBoot = true, backgroundMode = false))
        assertFalse(BootReceiver.shouldBeEnabled(startOnBoot = false, backgroundMode = true))
    }

    @Test
    fun theVisibleAppRefreshesARunningService() {
        governor.setVisible(true)
        governor.setSessionActive(true)
        governor.setVisible(false)
        governor.setVisible(true)
        assertEquals(2, fake.starts)
        assertEquals(0, fake.stops)
    }
}
