package io.github.vrcxandroid.host

import io.github.vrcxandroid.bridge.BridgeDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventRelayTest {
    private fun event(name: String, value: Int) = "{\"ev\":\"$name\",\"d\":$value}"

    @Test
    fun parsesTheEventNameOfDispatcherMessages() {
        assertEquals("insets", EventRelay.eventName("{\"ev\":\"insets\",\"d\":{\"top\":24}}"))
        assertEquals("focus", EventRelay.eventName("{\"ev\":\"focus\",\"d\":null}"))
        assertNull(EventRelay.eventName("{\"id\":1,\"ok\":true,\"r\":null}"))
        assertNull(EventRelay.eventName("{\"ev\":\"\"}"))
        assertNull(EventRelay.eventName("garbage"))
    }

    @Test
    fun matchesWhatBridgeDispatcherEmits() {
        val dispatcher = BridgeDispatcher(CoroutineScope(Dispatchers.Unconfined))
        val relay = EventRelay()
        val sent = ArrayList<String>()
        dispatcher.eventSink = relay::deliver
        relay.connect { sent += it }
        dispatcher.emit("launch-command", JsonPrimitive("user/usr_1"))
        dispatcher.emit("tts-event", buildJsonObject {
            put("id", 3)
            put("type", "end")
        })
        assertEquals(listOf("{\"ev\":\"launch-command\",\"d\":\"user/usr_1\"}", "{\"ev\":\"tts-event\",\"d\":{\"id\":3,\"type\":\"end\"}}"), sent)
        assertEquals("tts-event", EventRelay.eventName(sent[1]))
    }

    @Test
    fun eventsBeforeAPageConnectsAreDroppedExceptStateEvents() {
        val relay = EventRelay()
        assertFalse(relay.isPageConnected)
        relay.deliver(event("insets", 1))
        relay.deliver(event("focus", 2))
        relay.deliver(event("tts-voices", 3))
        relay.deliver(event("insets", 4))
        val sent = ArrayList<String>()
        relay.connect { sent += it }
        assertTrue(relay.isPageConnected)
        // Replayed in the order of their latest update; transient events are not replayed.
        assertEquals(listOf(event("tts-voices", 3), event("insets", 4)), sent)
        relay.deliver(event("launch-command", 5))
        assertEquals(event("launch-command", 5), sent.last())
    }

    @Test
    fun aReloadedPageGetsTheStateAgain() {
        val relay = EventRelay()
        val first = ArrayList<String>()
        relay.connect { first += it }
        relay.deliver(event("companion-state", 1))
        relay.deliver(event("network-changed", 2))
        val second = ArrayList<String>()
        relay.connect { second += it }
        assertEquals(listOf(event("companion-state", 1)), second)
        relay.deliver(event("game-state", 3))
        assertEquals(2, first.size)
        assertEquals(event("game-state", 3), second.last())
        assertEquals(event("game-state", 3), relay.last("game-state"))
    }

    @Test
    fun disconnectStopsDelivery() {
        val relay = EventRelay()
        val sent = ArrayList<String>()
        relay.connect { sent += it }
        relay.disconnect()
        assertFalse(relay.isPageConnected)
        relay.deliver(event("focus", 1))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun observersSeeEveryEvent() {
        val relay = EventRelay()
        val seen = ArrayList<String>()
        relay.observe { name, _ -> seen += name }
        relay.deliver(event("companion-state", 1))
        relay.deliver("not an event")
        relay.deliver(event("focus", 2))
        assertEquals(listOf("companion-state", "focus"), seen)
    }
}
