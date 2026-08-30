package com.vrcx.android.service

import com.vrcx.android.data.repository.AccountScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PipelineRuntimeStateTest {

    @Test
    fun `a new account replaces the old generation while a duplicate start reuses it`() = runTest {
        val accountScope = AccountScope().apply { bind("usr_old") }
        val state = PipelineRuntimeState(accountScope, backgroundScope)
        val oldOrigin = accountScope.current()
        val oldScopeReady = CompletableDeferred<CoroutineScope>()

        val first = state.begin { scope ->
            oldScopeReady.complete(scope)
            awaitCancellation()
        }
        runCurrent()
        val oldScope = oldScopeReady.await()

        assertTrue(first is PipelineStart.Started)
        assertTrue(state.claim(oldScope, oldOrigin, "old-token"))
        assertEquals(PipelineStart.AlreadyRunning, state.begin { error("duplicate startup") })

        accountScope.invalidate()
        accountScope.bind("usr_new")
        val newOrigin = accountScope.current()
        val newScopeReady = CompletableDeferred<CoroutineScope>()
        val replacement = state.begin { scope ->
            newScopeReady.complete(scope)
            awaitCancellation()
        } as PipelineStart.Started
        runCurrent()
        val newScope = newScopeReady.await()

        assertSame(oldScope, replacement.detached?.scope)
        assertTrue(state.claim(newScope, newOrigin, "new-token"))
        assertFalse(state.isCurrent(oldScope, oldOrigin))
        assertNull(state.detach(oldScope))
        assertTrue(state.isCurrent(newScope, newOrigin))

        replacement.detached?.scope?.cancel()
        state.detach(newScope)?.scope?.cancel()
    }
}
