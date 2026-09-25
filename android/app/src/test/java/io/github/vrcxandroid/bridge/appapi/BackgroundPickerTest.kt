package io.github.vrcxandroid.bridge.appapi

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** `OpenVrcPhotosFolder` asks for the photos folder without holding the AppApi lane. */
@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundPickerTest {
    private val noLog: (String, Throwable?) -> Unit = { _, _ -> }

    @Test
    fun launchReturnsAtOnceAndOnlyOnePickerIsOpen() = runTest {
        val answer = CompletableDeferred<String?>()
        var picks = 0
        val picker = BackgroundPicker(this, { picks++; answer.await() }, noLog)
        val got = mutableListOf<String>()

        assertTrue(picker.launch { got += it })
        runCurrent()
        assertTrue(picker.isOpen)
        // a second tap while the picker is up does not open another one
        assertFalse(picker.launch { got += "second:$it" })

        answer.complete("tree")
        runCurrent()
        assertEquals(listOf("tree"), got)
        assertFalse(picker.isOpen)
        assertEquals(1, picks)
    }

    @Test
    fun cancelledOrFailedPicksCallNothingAndFreeThePicker() = runTest {
        val logs = mutableListOf<String>()
        val cancelled = BackgroundPicker<String>(this, { null }, { m, _ -> logs += m })
        assertTrue(cancelled.launch { fail("the user cancelled") })
        runCurrent()
        assertFalse(cancelled.isOpen)
        assertTrue(logs.isEmpty())

        val failing = BackgroundPicker<String>(this, { throw IllegalStateException("no Activity") }, { m, _ -> logs += m })
        assertTrue(failing.launch { fail("the picker failed") })
        runCurrent()
        assertFalse(failing.isOpen)
        assertEquals(listOf("Picker failed"), logs)
        assertTrue(failing.launch { })
    }

    @Test
    fun aLostAnswerFreesThePickerAfterTheTimeout() = runTest {
        val picker = BackgroundPicker<String>(this, { awaitCancellation() }, noLog, timeoutMillis = 1_000)
        assertTrue(picker.launch { fail("no answer") })
        advanceTimeBy(999)
        runCurrent()
        assertTrue(picker.isOpen)
        advanceTimeBy(2)
        runCurrent()
        assertFalse(picker.isOpen)
    }
}
