package com.vrcx.android.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadStateTest {

    private val nothing: LoadState<Unit> = LoadState.NotLoaded

    @Test
    fun `the first load spins, a later one keeps the data on screen`() {
        val first = nothing.startLoad()
        assertEquals(LoadState.Loading, first)

        val loaded = first.completeLoad(Unit)
        val refreshing = loaded.startLoad()

        assertEquals(LoadState.Loaded(Unit, isRefreshing = true), refreshing)
    }

    @Test
    fun `a failed first load has nothing to show, a failed refresh keeps what it had`() {
        val failedFirst = nothing.startLoad().failLoad("boom")
        assertEquals(LoadState.Failed("boom"), failedFirst)

        val failedRefresh = nothing.completeLoad(Unit).startLoad().failLoad("boom")

        // The data outlives the failure, so the screen shows it with the message
        // beside it rather than replacing the whole thing with an error page.
        assertEquals(LoadState.Loaded(Unit, isRefreshing = false, staleError = "boom"), failedRefresh)
    }

    @Test
    fun `a warning is a partial success, not a failure`() {
        val loaded = nothing.startLoad().completeLoad(Unit, warning = "some details are missing")

        // staleError stays null: the load succeeded, it just succeeded partially.
        assertEquals(LoadState.Loaded(Unit, warning = "some details are missing"), loaded)
    }

    @Test
    fun `starting a load clears the previous attempt's messages`() {
        val stale = LoadState.Loaded(Unit, staleError = "boom", warning = "partial")

        val restarted = stale.startLoad()

        assertEquals(LoadState.Loaded(Unit, isRefreshing = true), restarted)
    }

    @Test
    fun `a cancelled load leaves nothing busy, so a retry can start`() {
        // The guard against a second concurrent load is isBusy, so a state that
        // stays busy after cancellation blocks every later attempt.
        assertEquals(LoadState.NotLoaded, nothing.startLoad().settleLoad())
        assertFalse(nothing.startLoad().settleLoad().isBusy)

        val loaded = nothing.completeLoad(Unit)
        assertEquals(loaded, loaded.startLoad().settleLoad())
    }

    @Test
    fun `settling a state that already decided changes nothing`() {
        val loaded = LoadState.Loaded(Unit, staleError = "boom")
        assertEquals(loaded, loaded.settleLoad())

        val failed: LoadState<Unit> = LoadState.Failed("boom")
        assertEquals(failed, failed.settleLoad())
    }

    @Test
    fun `busy and loaded describe the states a screen branches on`() {
        assertFalse(nothing.isBusy)
        assertFalse(nothing.isLoaded)

        assertTrue(LoadState.Loading.isBusy)
        assertFalse(LoadState.Loading.isLoaded)

        val loaded = LoadState.Loaded("data")
        assertFalse(loaded.isBusy)
        assertTrue(loaded.isLoaded)
        assertEquals("data", loaded.valueOrNull)

        assertTrue(loaded.startLoad().isBusy)
        assertTrue(loaded.startLoad().isLoaded)

        val failed: LoadState<String> = LoadState.Failed("boom")
        assertFalse(failed.isBusy)
        assertFalse(failed.isLoaded)
        assertNull(failed.valueOrNull)
    }
}
