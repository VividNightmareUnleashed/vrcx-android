package io.github.vrcxandroid.bridge

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Routes page messages `{id, c, m, a}` to [BridgeModule]s and sends back `{id, ok, r}` / `{id, ok:false, e}`
 * (docs/ARCHITECTURE.md §4.2-4.3). Serialized modules each get one thread and a FIFO queue; every call finishes before
 * the next one starts. Non-serialized modules run on [Dispatchers.IO].
 */
class BridgeDispatcher(private val scope: CoroutineScope) {
    private val modules = ConcurrentHashMap<String, BridgeModule>()
    private val lanes = ConcurrentHashMap<String, Channel<suspend () -> Unit>>()

    /** Set by the host: delivers a native → JS event message (already JSON text) to the page. */
    @Volatile
    var eventSink: ((String) -> Unit)? = null

    fun register(module: BridgeModule) {
        modules[module.className] = module
        if (module.serialized && !lanes.containsKey(module.className)) {
            val channel = Channel<suspend () -> Unit>(Channel.UNLIMITED)
            lanes[module.className] = channel
            val thread = Executors.newSingleThreadExecutor { r ->
                Thread(r, "bridge-${module.className}").apply { isDaemon = true }
            }.asCoroutineDispatcher()
            scope.launch(thread) {
                for (job in channel) {
                    try {
                        job()
                    } catch (t: Throwable) {
                        Log.e(TAG, "lane ${module.className} job failed", t)
                    }
                }
            }
        }
    }

    fun module(className: String): BridgeModule? = modules[className]

    /** Entry point for one raw message from the page. [reply] may be called from any thread. */
    fun handle(raw: String, reply: (String) -> Unit) {
        val msg = try {
            BridgeJson.parseToJsonElement(raw) as? JsonObject
        } catch (e: Exception) {
            Log.w(TAG, "unparseable bridge message", e)
            null
        } ?: return
        val id = (msg["id"] as? JsonPrimitive)?.longOrNull ?: return
        val className = (msg["c"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val method = (msg["m"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val args = msg["a"] as? JsonArray ?: JsonArray(emptyList())

        val module = modules[className]
        if (module == null) {
            reply(errorReply(id, "MissingMethodException: Method $method does not exist on class $className"))
            return
        }
        val job: suspend () -> Unit = {
            val out = try {
                okReply(id, module.invoke(method, args))
            } catch (t: Throwable) {
                if (t !is DotNetException) Log.w(TAG, "$className.$method failed", t)
                errorReply(id, errorText(t))
            }
            reply(out)
        }
        val lane = lanes[className]
        if (lane != null) lane.trySend(job) else scope.launch(Dispatchers.IO) { job() }
    }

    /**
     * Runs native work in order with the page's calls to [className] (for example closing the SQLite connection
     * before a database import). Runs immediately on the IO dispatcher for non-serialized modules.
     */
    suspend fun <T> runSerialized(className: String, block: suspend () -> T): T {
        val lane = lanes[className] ?: return block()
        val result = CompletableDeferred<T>()
        lane.send {
            try {
                result.complete(block())
            } catch (t: Throwable) {
                result.completeExceptionally(t)
            }
        }
        return result.await()
    }

    /** Waits until every serialized module has finished the calls queued so far. */
    suspend fun drain() {
        for (name in lanes.keys.toList()) runSerialized(name) {}
    }

    /** Sends a native → JS event `{ev, d}`. */
    fun emit(event: String, data: JsonElement? = null) {
        val text = buildJsonObject {
            put("ev", event)
            put("d", data ?: JsonNull)
        }.toString()
        eventSink?.invoke(text)
    }

    companion object {
        private const val TAG = "VRCXBridge"

        fun okReply(id: Long, result: JsonElement): String = buildJsonObject {
            put("id", id)
            put("ok", true)
            put("r", result)
        }.toString()

        fun errorReply(id: Long, error: String): String = buildJsonObject {
            put("id", id)
            put("ok", false)
            put("e", error)
        }.toString()
    }
}
