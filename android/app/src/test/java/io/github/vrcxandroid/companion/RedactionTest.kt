package io.github.vrcxandroid.companion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RedactionTest {
    @Test
    fun redactedThrowableKeepsClassesAndFramesButNoMessages() {
        val cause = IOException("JSON input: ...\"Value\":\"authcookie_SECRET\"")
        val error = IllegalStateException("log line: [Behaviour] OnPlayerJoined SECRET", cause)
        val redacted = error.redacted()

        val text = redacted.stackTraceToString()
        assertFalse(text, text.contains("SECRET"))
        assertTrue(text, text.contains("java.lang.IllegalStateException"))
        assertTrue(text, text.contains("java.io.IOException"))
        assertArrayEquals(error.stackTrace, redacted.stackTrace)
        assertArrayEquals(cause.stackTrace, redacted.cause!!.stackTrace)
        assertEquals("IOException", cause.kind)
    }

    @Test
    fun deepCauseChainsAreBounded() {
        var e: Throwable = RuntimeException("0")
        repeat(50) { e = RuntimeException("$it", e) }
        var depth = 0
        var r: Throwable? = e.redacted()
        while (r != null) {
            depth++
            r = r.cause
        }
        assertTrue("depth $depth", depth in 2..10)
    }
}
