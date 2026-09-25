package io.github.vrcxandroid.companion

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class DiscoveryTest {
    private val loopback = InetAddress.getByName("127.0.0.1")

    private fun reply(id: String = "companion-1", v: Int = 1) = buildJsonObject {
        put("t", "vrcx-companion")
        put("v", v)
        put("id", id)
        put("name", "TESTPC")
        put("port", 49470)
        put("fp", "fp-abc")
        put("pairing", true)
    }

    @Test
    fun collectsRepliesAndHoldsTheMulticastLockOnlyWhileDiscovering() {
        val junk = listOf(
            "not json".toByteArray(),
            reply(v = 2).toString().toByteArray(),
            reply(id = "../bad").toString().toByteArray(),
            buildJsonObject { put("t", "other") }.toString().toByteArray(),
        )
        FakeDiscoveryResponder({ reply() }, junk).use { responder ->
            val lock = CountingLock()
            val discovery = CompanionDiscovery(lock, responder.port) { listOf(loopback) }
            val found = discovery.discover(600)
            assertEquals(1, found.size)
            val c = found.single()
            assertEquals("companion-1", c.id)
            assertEquals("TESTPC", c.name)
            assertEquals("127.0.0.1", c.host)
            assertEquals(49470, c.port)
            assertEquals("fp-abc", c.fp)
            assertTrue(c.pairing)
            assertEquals(1, lock.acquired.get())
            assertEquals(1, lock.released.get())
            assertTrue(responder.requests.isNotEmpty())
            assertEquals("{\"t\":\"vrcx-discover\",\"v\":1}", responder.requests.first())
            val json = c.toJson()
            assertEquals("127.0.0.1", json.s("host"))
        }
    }

    @Test
    fun stopsEarlyWhenTheWantedCompanionAnswers() {
        FakeDiscoveryResponder({ reply() }).use { responder ->
            val discovery = CompanionDiscovery(MulticastLockHandle.NONE, responder.port) { listOf(loopback) }
            val start = System.nanoTime()
            val found = discovery.discover(10_000) { it.id == "companion-1" }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            assertEquals(1, found.size)
            assertTrue("took $elapsedMs ms", elapsedMs < 5_000)
        }
    }

    @Test
    fun noAnswerReturnsEmptyAfterTheTimeout() {
        val discovery = CompanionDiscovery(MulticastLockHandle.NONE, 9) { listOf(loopback) }
        assertTrue(discovery.discover(300).isEmpty())
    }
}
