package com.vrcx.android.data.websocket

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
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

class VRChatWebSocket(
    private val json: Json,
    baseClient: OkHttpClient,
) {
    private val TAG = "VRChatWebSocket"
    private val WEBSOCKET_URL = "wss://pipeline.vrchat.cloud"
    @Volatile private var reconnectAttempt = 0
    private val connectionGeneration = AtomicLong(0)

    private val _events = MutableSharedFlow<PipelineEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<PipelineEvent> = _events.asSharedFlow()

    private val _state = MutableStateFlow(WebSocketState.DISCONNECTED)
    val state: StateFlow<WebSocketState> = _state.asStateFlow()

    private var webSocket: WebSocket? = null
    @Volatile private var shouldReconnect = false
    @Volatile private var reconnectJob: Job? = null
    private var lastMessage: String? = null
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var frameQueue = newFrameQueue()

    // WebSocket auth is carried in the URL, so this client intentionally avoids
    // API interceptors that could log, retry, or emit global auth events.
    private val client = baseClient.newBuilder()
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
        resetProcessingScope()
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

    private fun newFrameQueue(): Channel<String> = Channel(
        capacity = PIPELINE_FRAME_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

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
        const val PIPELINE_FRAME_BUFFER_CAPACITY = 256
        const val WEBSOCKET_PING_INTERVAL_SECONDS = 30L
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
        rawContent is kotlinx.serialization.json.JsonPrimitive && rawContent.isString -> {
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
