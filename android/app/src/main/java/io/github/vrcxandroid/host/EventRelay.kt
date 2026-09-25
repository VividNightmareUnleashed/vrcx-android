package io.github.vrcxandroid.host

/**
 * Delivers native → JS event messages (`{"ev":..., "d":...}` text built by BridgeDispatcher.emit) to the page that
 * connected last. State-like events are remembered and replayed when a page connects (first load, reload, renderer
 * recovery), so the page never misses the current insets, voices or companion state.
 *
 * Pure JVM code: the host wires [send] to `JavaScriptReplyProxy.postMessage` on the main thread.
 */
class EventRelay(private val stickyEvents: Set<String> = DEFAULT_STICKY) {
    private val sticky = LinkedHashMap<String, String>()
    private var send: ((String) -> Unit)? = null
    private val observers = ArrayList<(String, String) -> Unit>()

    /** True once a page has said hello and until it goes away. */
    val isPageConnected: Boolean
        @Synchronized get() = send != null

    /** Observes every event (name, full message text), on the emitting thread. */
    @Synchronized
    fun observe(observer: (name: String, text: String) -> Unit) {
        observers += observer
    }

    /**
     * Entry point for BridgeDispatcher.eventSink. Any thread. The sender only enqueues (it posts to the main thread), so
     * it is called under the lock to keep events in order with a concurrent [connect] replay.
     */
    fun deliver(text: String) {
        val name = eventName(text) ?: return
        val obs: List<(String, String) -> Unit>
        synchronized(this) {
            if (name in stickyEvents) {
                sticky.remove(name)
                sticky[name] = text
            }
            obs = observers.toList()
            send?.let { runCatching { it(text) } }
        }
        obs.forEach { runCatching { it(name, text) } }
    }

    /** A page connected: route events to [sender] and replay the remembered state events in arrival order. */
    @Synchronized
    fun connect(sender: (String) -> Unit) {
        send = sender
        sticky.values.forEach { runCatching { sender(it) } }
    }

    @Synchronized
    fun disconnect() {
        send = null
    }

    /** Last remembered message text of a sticky event, or null. */
    @Synchronized
    fun last(name: String): String? = sticky[name]

    companion object {
        val DEFAULT_STICKY = setOf("insets", "tts-voices", "companion-state", "game-state", "visibility")

        private const val PREFIX = "{\"ev\":\""

        /** Event name of a message built by BridgeDispatcher.emit (the "ev" key comes first). */
        fun eventName(text: String): String? {
            if (!text.startsWith(PREFIX)) return null
            val end = text.indexOf('"', PREFIX.length)
            if (end <= PREFIX.length) return null
            val name = text.substring(PREFIX.length, end)
            return if ('\\' in name) null else name
        }
    }
}
