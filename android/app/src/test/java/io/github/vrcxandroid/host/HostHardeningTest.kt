package io.github.vrcxandroid.host

import io.github.vrcxandroid.bridge.SafeLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.PrintWriter
import java.io.StringWriter

class HostHardeningTest {
    @Test
    fun localFilesAreOnlyServedAsPassiveTypes() {
        assertEquals("image/png", LocalFileServer.mimeType("a.PNG"))
        assertEquals("image/jpeg", LocalFileServer.mimeType("a.jpeg"))
        assertEquals("image/webp", LocalFileServer.mimeType("a.webp"))
        assertEquals("video/mp4", LocalFileServer.mimeType("a.mp4"))
        assertEquals("application/json", LocalFileServer.mimeType("a.json"))
        assertEquals("text/plain", LocalFileServer.mimeType("a.log"))
        assertEquals("text/calendar", LocalFileServer.mimeType("event.ics"))
        for (active in listOf("x.html", "x.htm", "x.svg", "x.js", "x.mjs", "x.css", "x.xhtml", "x.xml", "noext")) {
            assertEquals(active, "application/octet-stream", LocalFileServer.mimeType(active))
        }
    }

    @Test
    fun localResponsesAreSandboxedAndNotSniffed() {
        assertEquals("nosniff", LocalFileServer.LOCAL_HEADERS["X-Content-Type-Options"])
        val csp = LocalFileServer.LOCAL_HEADERS["Content-Security-Policy"]!!
        assertTrue(csp, csp.startsWith("sandbox") && "default-src 'none'" in csp)
    }

    @Test
    fun onlyTheAppOriginMayTalkToTheBridge() {
        assertTrue(HostUrls.isAppOrigin("https://appassets.androidplatform.net"))
        assertTrue(HostUrls.isAppOrigin("https://appassets.androidplatform.net/"))
        assertFalse(HostUrls.isAppOrigin("null"))
        assertFalse(HostUrls.isAppOrigin(null))
        assertFalse(HostUrls.isAppOrigin("http://appassets.androidplatform.net"))
        assertFalse(HostUrls.isAppOrigin("https://appassets.androidplatform.net.evil.example"))
        assertFalse(HostUrls.isAppOrigin("https://evil.example"))
    }

    @Test
    fun companionStateSummaryReadsTheEvent() {
        val s = CompanionStateSummary.fromEvent("{\"ev\":\"companion-state\",\"d\":{\"status\":\"connected\",\"machineName\":\"PC\"}}")!!
        assertEquals("connected", s.status)
        assertEquals("PC", s.machineName)
        assertTrue(s.connected)
        val idle = CompanionStateSummary.fromEvent("{\"ev\":\"companion-state\",\"d\":{\"status\":\"connecting\",\"machineName\":null}}")!!
        assertFalse(idle.connected)
        assertNull(idle.machineName)
        assertNull(CompanionStateSummary.fromEvent("not json"))
        assertNull(CompanionStateSummary.fromEvent("{\"ev\":\"companion-state\"}"))
    }

    @Test
    fun redactedThrowablesKeepFramesButNoMessages() {
        val cause = IllegalArgumentException("JSON input: {\"auth\":\"SECRET\"}")
        val error = IllegalStateException("password=SECRET", cause)
        val redacted = SafeLog.redacted(error)
        val text = StringWriter().also { redacted.printStackTrace(PrintWriter(it)) }.toString()
        assertFalse(text, "SECRET" in text)
        assertTrue(text, "java.lang.IllegalStateException" in text && "java.lang.IllegalArgumentException" in text)
        assertTrue(text, "HostHardeningTest" in text)
        assertEquals("(IllegalStateException)", SafeLog.kind(error))
    }
}
