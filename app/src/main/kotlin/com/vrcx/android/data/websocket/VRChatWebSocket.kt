package com.vrcx.android.data.websocket

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit

enum class WebSocketState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

/**
 * The one OkHttp client the pipeline socket may be built on.
 *
 * The pipeline auth token travels in the connection URL, so any client carrying
 * a logging interceptor writes a live session token to logcat, and one carrying
 * the API cookie jar routes pipeline failures through the global session-expiry
 * path. Both guarantees used to rest on a `@Named` string being spelled right at
 * the injection point; as its own type, handing the socket the API client is a
 * compile error instead. A plain wrapper rather than a value class: Dagger
 * mangles the JVM name of a `@Provides` method that returns one and then cannot
 * name the factory it generates for it.
 */
class PipelineOkHttpClient(val client: OkHttpClient)

class VRChatWebSocket(
    private val json: Json,
    baseClient: PipelineOkHttpClient,
    /**
     * Called when VRChat rejects the handshake itself rather than the transport
     * failing. The auth token sits in the connection URL and is captured once,
     * so retrying it on the backoff can never succeed — only a caller holding
     * the session can produce a new one.
     */
    private val onHandshakeRejected: (() -> Unit)? = null,
) {
    private val TAG = "VRChatWebSocket"
    private val WEBSOCKET_URL = "wss://pipeline.vrchat.cloud"
    @Volatile private var reconnectAttempt = 0
    private val connectionGeneration = AtomicLong(0)

    private val _events = MutableSharedFlow<PipelineEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<PipelineEvent> = _events.asSharedFlow()

    private val _state = MutableStateFlow(WebSocketState.DISCONNECTED)
    val state: StateFlow<WebSocketState> = _state.asStateFlow()

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var shouldReconnect = false
    @Volatile private var reconnectJob: Job? = null
    // Written on the OkHttp reader thread, reset from whichever thread calls
    // connect(). Without the barrier the reset can be invisible to the new
    // connection's reader and its first frame gets filtered as a duplicate.
    @Volatile private var lastMessage: String? = null
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var frameQueue = newFrameQueue()

    // WebSocket auth is carried in the URL, so this client intentionally avoids
    // API interceptors that could log, retry, or emit global auth events.
    private val client = baseClient.client.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(WEBSOCKET_PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .build()

    init {
        launchFramePump()
    }

    fun connect(authToken: String) {
        val generation = connectionGeneration.incrementAndGet()
        shouldReconnect = true
        lastMessage = null
        _state.value = WebSocketState.CONNECTING
        // Close before replacing, as disconnect() and reconnectNow() both do.
        // The generation guard only silences the old listener; the socket itself
        // would stay attached to the pipeline holding a connection and a ping timer.
        webSocket?.close(1000, "Reconnecting")

        val request = Request.Builder()
            .url("$WEBSOCKET_URL/?auth=$authToken")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isCurrent(generation)) return
                Log.d(TAG, "WebSocket connected")
                reconnectAttempt = 0
                _state.value = WebSocketState.CONNECTED
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!isCurrent(generation)) return
                // Duplicate filtering
                if (text == lastMessage) return
                lastMessage = text
                frameQueue.trySend(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (!isCurrent(generation)) return
                Log.d(TAG, "WebSocket closing: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!isCurrent(generation)) return
                Log.d(TAG, "WebSocket closed: $code $reason")
                _state.value = WebSocketState.DISCONNECTED
                attemptReconnect(authToken, generation)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!isCurrent(generation)) return
                Log.e(TAG, "WebSocket failure: ${t.message}")
                _state.value = WebSocketState.DISCONNECTED
                // A rejected handshake is not a transport failure. Spinning the
                // backoff on a token VRChat has already refused reports
                // RECONNECTING forever while every realtime event stops.
                if (response?.code == HTTP_UNAUTHORIZED || response?.code == HTTP_FORBIDDEN) {
                    Log.w(TAG, "Pipeline rejected the auth token")
                    shouldReconnect = false
                    onHandshakeRejected?.invoke()
                    return
                }
                attemptReconnect(authToken, generation)
            }
        })
    }

    fun disconnect() {
        shouldReconnect = false
        connectionGeneration.incrementAndGet()
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        _state.value = WebSocketState.DISCONNECTED
        // Terminal for this instance — both callers drop it on the next line.
        // Re-arming the frame pump here would park a coroutine on a channel
        // nothing will ever write to; only reconnectNow() needs a fresh one.
        scope.cancel()
        reconnectJob = null
    }

    fun reconnectNow(authToken: String) {
        shouldReconnect = false
        connectionGeneration.incrementAndGet()
        webSocket?.close(1000, "Reconnecting")
        webSocket = null
        resetProcessingScope()
        reconnectAttempt = 0
        connect(authToken)
    }

    private fun attemptReconnect(authToken: String, generation: Long) {
        if (!shouldReconnect || !isCurrent(generation)) return
        if (reconnectJob?.isActive == true) return
        if (reconnectAttempt < Int.MAX_VALUE) reconnectAttempt++
        _state.value = WebSocketState.RECONNECTING
        val delayMs = calculateReconnectDelayMs(reconnectAttempt)
        Log.d(TAG, "Reconnect attempt $reconnectAttempt in ${delayMs}ms")
        reconnectJob = scope.launch {
            delay(delayMs)
            if (shouldReconnect && isCurrent(generation)) {
                reconnectJob = null
                connect(authToken)
            }
        }
    }

    private fun isCurrent(generation: Long): Boolean = generation == connectionGeneration.get()

    // Unbounded on purpose. A bounded queue can only shed load by discarding
    // frames, and a discarded frame is a friend transition or an invite the app
    // never learns about — the pipeline has no replay to recover it. Blocking
    // the producer instead is not an option either: trySend runs on the OkHttp
    // reader thread, which also answers pings, so stalling it would fail the
    // 30s ping timeout and drop the very connection this protects. Frames are
    // small and the backlog only builds during the post-connect burst.
    private fun newFrameQueue(): Channel<String> = Channel(capacity = Channel.UNLIMITED)

    private fun launchFramePump() {
        scope.launch {
            for (text in frameQueue) {
                parsePipelineMessage(json, text)?.let { event -> _events.emit(event) }
            }
        }
    }

    private fun resetProcessingScope() {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        reconnectJob = null
        frameQueue = newFrameQueue()
        launchFramePump()
    }

    private companion object {
        const val WEBSOCKET_PING_INTERVAL_SECONDS = 30L
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
    }
}

private const val BASE_RECONNECT_DELAY_MS = 5_000L
private const val MAX_RECONNECT_DELAY_MS = 300_000L
private const val MAX_RECONNECT_JITTER_MS = 2_000L

internal fun calculateReconnectDelayMs(
    attempt: Int,
    jitterMs: Long = (0L..MAX_RECONNECT_JITTER_MS).random(),
): Long {
    val exponent = (attempt.coerceAtLeast(1) - 1).coerceAtMost(17)
    val baseDelay = minOf(
        BASE_RECONNECT_DELAY_MS * (1L shl exponent),
        MAX_RECONNECT_DELAY_MS,
    )
    return baseDelay + jitterMs.coerceIn(0L, MAX_RECONNECT_JITTER_MS)
}

/**
 * Whether a connectivity change should force an immediate reconnect rather than
 * leave the socket on its own backoff.
 *
 * A break-before-make loss (airplane mode, a lift, a radio reset) clears the
 * previous network before the new one arrives, so `networkWasReplaced` is false;
 * the failed connect meanwhile put the socket in RECONNECTING with a delay that
 * doubles toward five minutes. Anything other than an established connection has
 * to count as "reconnect now", or the app sits out that whole delay after the
 * radio is already back. CONNECTED is excluded so a duplicate onAvailable can't
 * tear down a healthy socket.
 */
internal fun shouldForceReconnect(networkWasReplaced: Boolean, state: WebSocketState): Boolean =
    networkWasReplaced || state != WebSocketState.CONNECTED

/**
 * Parses a single VRChat pipeline frame into a typed PipelineEvent. Returns
 * null for malformed messages or messages without a `type` field. The frame's
 * `content` field can be either a JSON string (needs double-decoding) or an
 * inline JSON object — both are supported. Extracted as a top-level pure
 * function so the parser is testable without an OkHttp session.
 */
internal fun parsePipelineMessage(json: Json, text: String): PipelineEvent? {
    val msg = try {
        json.parseToJsonElement(text).jsonObject
    } catch (_: Exception) {
        return null
    }
    val type = msg["type"]?.jsonPrimitive?.content ?: return null
    val rawContent = msg["content"]
    val content: JsonElement? = when {
        rawContent == null -> null
        // JsonNull is a JsonPrimitive, so without this arm a literal
        // `"content": null` is carried as a non-null element and every
        // downstream `content?.jsonObject` throws instead of short-circuiting.
        rawContent is JsonNull -> null
        rawContent is JsonPrimitive && rawContent.isString -> {
            try { json.parseToJsonElement(rawContent.content) } catch (_: Exception) { null }
        }
        else -> rawContent
    }

    return when (type) {
        "friend-online" -> PipelineEvent.FriendOnline(content)
        "friend-offline" -> PipelineEvent.FriendOffline(content)
        "friend-active" -> PipelineEvent.FriendActive(content)
        "friend-update" -> PipelineEvent.FriendUpdate(content)
        "friend-location" -> PipelineEvent.FriendLocation(content)
        "friend-add" -> PipelineEvent.FriendAdd(content)
        "friend-delete" -> PipelineEvent.FriendDelete(content)
        "user-update" -> PipelineEvent.UserUpdate(content)
        "user-location" -> PipelineEvent.UserLocation(content)
        "notification" -> PipelineEvent.Notification(content)
        "notification-v2" -> PipelineEvent.NotificationV2(content)
        "notification-v2-delete" -> PipelineEvent.NotificationV2Delete(content)
        "notification-v2-update" -> PipelineEvent.NotificationV2Update(content)
        "see-notification" -> PipelineEvent.SeeNotification(content)
        "hide-notification" -> PipelineEvent.HideNotification(content)
        "response-notification" -> PipelineEvent.ResponseNotification(content)
        "clear-notification" -> PipelineEvent.ClearNotification
        "group-joined" -> PipelineEvent.GroupJoined(content)
        "group-left" -> PipelineEvent.GroupLeft(content)
        "group-role-updated" -> PipelineEvent.GroupRoleUpdated(content)
        "group-member-updated" -> PipelineEvent.GroupMemberUpdated(content)
        "content-refresh" -> PipelineEvent.ContentRefresh(content)
        "instance-closed" -> PipelineEvent.InstanceClosed(content)
        else -> PipelineEvent.Unknown(type, content)
    }
}
