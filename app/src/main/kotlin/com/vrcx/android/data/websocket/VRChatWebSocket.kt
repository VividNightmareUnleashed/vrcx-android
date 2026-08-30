package com.vrcx.android.data.websocket

import android.util.Log
import com.vrcx.android.di.IoDispatcher
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.utf8Size

enum class WebSocketState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

private const val TAG = "VRChatWebSocket"

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
    @IoDispatcher ioDispatcher: CoroutineDispatcher,
    /**
     * Called when VRChat rejects the handshake itself rather than the transport
     * failing. The auth token sits in the connection URL and is captured once,
     * so retrying it on the backoff can never succeed — only a caller holding
     * the session can produce a new one.
     */
    private val onHandshakeRejected: (() -> Unit)? = null,
) {
    private val connectionLock = Any()
    private var disposed = false
    private var reconnectAttempt = 0
    private var connectionGeneration = 0L

    // The channel below is the one buffer between OkHttp and event consumers.
    // Keeping another backlog here would retain stale events after recovery.
    private val _events = MutableSharedFlow<PipelineEvent>()
    val events: SharedFlow<PipelineEvent> = _events.asSharedFlow()

    private val _state = MutableStateFlow(WebSocketState.DISCONNECTED)
    val state: StateFlow<WebSocketState> = _state.asStateFlow()

    private var webSocket: WebSocket? = null
    private var shouldReconnect = false
    private var reconnectJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val frameBuffer = PipelineFrameBuffer(
        scope = scope,
        connectionLock = connectionLock,
        onFrame = ::processFrame,
        onRecovery = { _events.emit(PipelineEvent.StreamGap) },
    )

    // WebSocket auth is carried in the URL, so this client intentionally avoids
    // API interceptors that could log, retry, or emit global auth events.
    private val client = baseClient.client.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(WEBSOCKET_PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .build()

    fun connect(authToken: String) = synchronized(connectionLock) {
        if (disposed || frameBuffer.recoveryPending) return@synchronized
        connectLocked(authToken)
    }

    private fun connectLocked(authToken: String) {
        reconnectJob?.cancel()
        reconnectJob = null
        connectionGeneration++
        val generation = connectionGeneration
        shouldReconnect = true
        _state.value = WebSocketState.CONNECTING
        // Close before replacing, as disconnect() and reconnectNow() both do.
        // The generation guard only silences the old listener; the socket itself
        // would stay attached to the pipeline holding a connection and a ping timer.
        webSocket?.close(NORMAL_CLOSURE_CODE, "Reconnecting")

        val request = Request.Builder()
            .url("$WEBSOCKET_URL/?auth=$authToken")
            .build()

        webSocket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                private var acceptingFrames = true
                private var lastMessage: String? = null

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    synchronized(connectionLock) {
                        if (!isCurrent(generation)) return
                        Log.d(TAG, "WebSocket connected")
                        reconnectAttempt = 0
                        _state.value = WebSocketState.CONNECTED
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val byteCount = validatedFrameByteCount(text)
                    if (byteCount == null) {
                        rejectConnection(webSocket, FrameRejection.OVERSIZED)
                    } else {
                        acceptFrame(webSocket, text, byteCount)
                    }
                }

                private fun acceptFrame(webSocket: WebSocket, text: String, byteCount: Long) {
                    var rejection: FrameRejection? = null
                    synchronized(connectionLock) {
                        val canAccept =
                            isCurrent(generation) &&
                                acceptingFrames &&
                                !frameBuffer.recoveryPending &&
                                text != lastMessage
                        if (canAccept) {
                            if (frameBuffer.tryEnqueue(text, byteCount)) {
                                lastMessage = text
                            } else {
                                rejection = rejectFramesLocked(FrameRejection.BACKLOG_OVERFLOW)
                            }
                        }
                    }
                    rejection?.let { abortForRecovery(webSocket, it) }
                }

                private fun rejectFramesLocked(rejection: FrameRejection): FrameRejection? {
                    if (!acceptingFrames || frameBuffer.recoveryPending) return null
                    frameBuffer.beginRecovery()
                    acceptingFrames = false
                    shouldReconnect = false
                    _state.value = WebSocketState.RECONNECTING
                    return rejection
                }

                private fun rejectConnection(webSocket: WebSocket, rejection: FrameRejection) {
                    val accepted = synchronized(connectionLock) {
                        if (!isCurrent(generation)) return@synchronized null
                        rejectFramesLocked(rejection)
                    }
                    accepted?.let { abortForRecovery(webSocket, it) }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    synchronized(connectionLock) {
                        if (!isCurrent(generation)) return
                        Log.d(TAG, "WebSocket closing: $code $reason")
                        webSocket.close(NORMAL_CLOSURE_CODE, null)
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    synchronized(connectionLock) {
                        if (!isCurrent(generation)) return
                        Log.d(TAG, "WebSocket closed: $code $reason")
                        _state.value = WebSocketState.DISCONNECTED
                    }
                    attemptReconnect(authToken, generation)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val handshakeRejected = synchronized(connectionLock) {
                        if (!isCurrent(generation)) return
                        Log.e(TAG, "WebSocket failure: ${t.message}")
                        _state.value = WebSocketState.DISCONNECTED
                        val rejected = response?.code == HTTP_UNAUTHORIZED ||
                            response?.code == HTTP_FORBIDDEN
                        if (rejected) {
                            Log.w(TAG, "Pipeline rejected the auth token")
                            shouldReconnect = false
                        }
                        rejected
                    }
                    // A rejected handshake is not a transport failure. Spinning the
                    // backoff on a token VRChat has already refused reports
                    // RECONNECTING forever while every realtime event stops.
                    if (handshakeRejected) {
                        onHandshakeRejected?.invoke()
                        return
                    }
                    attemptReconnect(authToken, generation)
                }
            },
        )
    }

    fun disconnect() = synchronized(connectionLock) {
        if (disposed) return@synchronized
        disposed = true
        shouldReconnect = false
        connectionGeneration++
        webSocket?.close(NORMAL_CLOSURE_CODE, "Client disconnect")
        webSocket = null
        _state.value = WebSocketState.DISCONNECTED
        // Terminal for this instance. Late service callbacks may still hold this
        // object, so disposed also prevents them from reviving the connection.
        scope.cancel()
        frameBuffer.cancel()
        reconnectJob = null
    }

    fun reconnectNow(authToken: String) = synchronized(connectionLock) {
        if (disposed || frameBuffer.recoveryPending) return@synchronized
        reconnectLocked(authToken, "Reconnecting")
    }

    fun reconnectAfterRecovery(authToken: String) = synchronized(connectionLock) {
        if (disposed || !frameBuffer.recoveryPending) return@synchronized
        frameBuffer.completeRecovery()
        reconnectLocked(authToken, "State recovered")
    }

    private fun reconnectLocked(authToken: String, reason: String) {
        shouldReconnect = false
        connectionGeneration++
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(NORMAL_CLOSURE_CODE, reason)
        webSocket = null
        reconnectAttempt = 0
        connectLocked(authToken)
    }

    private fun attemptReconnect(authToken: String, generation: Long) {
        synchronized(connectionLock) {
            if (disposed || !shouldReconnect || !isCurrent(generation)) return
            if (reconnectJob?.isActive == true) return
            if (reconnectAttempt < Int.MAX_VALUE) reconnectAttempt++
            _state.value = WebSocketState.RECONNECTING
            val delayMs = calculateReconnectDelayMs(reconnectAttempt)
            Log.d(TAG, "Reconnect attempt $reconnectAttempt in ${delayMs}ms")
            reconnectJob = scope.launch {
                delay(delayMs)
                synchronized(connectionLock) {
                    if (!disposed && shouldReconnect && isCurrent(generation)) {
                        reconnectJob = null
                        connectLocked(authToken)
                    }
                }
            }
        }
    }

    private fun isCurrent(generation: Long): Boolean = generation == connectionGeneration

    private suspend fun processFrame(frame: IncomingItem.Frame) {
        val parsed = runCatching { parsePipelineMessage(json, frame.text) }
        parsed.exceptionOrNull()?.let { error ->
            Log.w(TAG, "Discarding invalid pipeline frame", error)
        }
        parsed.getOrNull()?.let { event -> _events.emit(event) }
    }

    private fun abortForRecovery(webSocket: WebSocket, rejection: FrameRejection) {
        Log.w(TAG, "Aborting pipeline after ${rejection.reason}")
        webSocket.cancel()
    }

    private companion object {
        const val WEBSOCKET_URL = "wss://pipeline.vrchat.cloud"
        const val WEBSOCKET_PING_INTERVAL_SECONDS = 30L
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val NORMAL_CLOSURE_CODE = 1000
    }
}

private sealed interface IncomingItem {
    data class Frame(val text: String, val byteCount: Long) : IncomingItem
    data object RecoveryBarrier : IncomingItem
}

/**
 * The one bounded handoff between OkHttp's reader and pipeline event consumers.
 * Its extra channel slot is reserved for a recovery barrier, which follows the
 * complete accepted FIFO prefix when admission reaches either bound. Admission
 * and recovery methods run while [connectionLock] is held, keeping queue accounting
 * atomic with the owning connection's recovery state.
 */
private class PipelineFrameBuffer(
    scope: CoroutineScope,
    private val connectionLock: Any,
    private val onFrame: suspend (IncomingItem.Frame) -> Unit,
    private val onRecovery: suspend () -> Unit,
) {
    private val channel = Channel<IncomingItem>(capacity = PIPELINE_FRAME_QUEUE_CAPACITY + 1)
    private var retainedFrames = 0
    private var retainedBytes = 0L

    var recoveryPending = false
        private set

    init {
        scope.launch {
            for (item in channel) {
                when (item) {
                    is IncomingItem.Frame -> deliverFrame(item)
                    IncomingItem.RecoveryBarrier -> onRecovery()
                }
            }
        }
    }

    fun tryEnqueue(text: String, byteCount: Long): Boolean {
        if (isPipelineBufferFull(retainedFrames, retainedBytes, byteCount)) return false
        if (channel.trySend(IncomingItem.Frame(text, byteCount)).isFailure) return false
        retainedFrames++
        retainedBytes += byteCount
        return true
    }

    fun beginRecovery() {
        check(channel.trySend(IncomingItem.RecoveryBarrier).isSuccess) {
            "The reserved pipeline recovery slot was unavailable"
        }
        recoveryPending = true
    }

    fun completeRecovery() {
        recoveryPending = false
    }

    fun cancel() {
        channel.cancel()
    }

    private suspend fun deliverFrame(frame: IncomingItem.Frame) {
        try {
            onFrame(frame)
        } finally {
            synchronized(connectionLock) {
                retainedFrames--
                retainedBytes -= frame.byteCount
            }
        }
    }
}

private enum class FrameRejection(val reason: String) {
    OVERSIZED("Pipeline frame too large"),
    BACKLOG_OVERFLOW("Pipeline consumer overloaded"),
}

internal const val PIPELINE_FRAME_QUEUE_CAPACITY = 1_024
internal const val MAX_PIPELINE_FRAME_BYTES = 256L * 1024L
internal const val MAX_PIPELINE_BUFFER_BYTES = 16L * 1024L * 1024L

private fun validatedFrameByteCount(text: String): Long? {
    // UTF-8 bytes cannot be fewer than UTF-16 code units, so oversized text is
    // rejected without first scanning the complete frame.
    val couldFit = text.length.toLong() <= MAX_PIPELINE_FRAME_BYTES
    return if (couldFit) {
        text.utf8Size().takeIf { it <= MAX_PIPELINE_FRAME_BYTES }
    } else {
        null
    }
}

internal fun isPipelineBufferFull(retainedFrames: Int, retainedBytes: Long, nextFrameBytes: Long): Boolean =
    retainedFrames >= PIPELINE_FRAME_QUEUE_CAPACITY ||
        retainedBytes + nextFrameBytes > MAX_PIPELINE_BUFFER_BYTES

private const val BASE_RECONNECT_DELAY_MS = 5_000L
private const val MAX_RECONNECT_DELAY_MS = 300_000L
private const val MAX_RECONNECT_JITTER_MS = 2_000L
private const val MAX_RECONNECT_EXPONENT = 6

internal fun calculateReconnectDelayMs(attempt: Int, jitterMs: Long = (0L..MAX_RECONNECT_JITTER_MS).random()): Long {
    val exponent = (attempt.coerceAtLeast(1) - 1).coerceAtMost(MAX_RECONNECT_EXPONENT)
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
internal fun parsePipelineMessage(json: Json, text: String): PipelineEvent? =
    decodePipelineEnvelope(json, text)?.let { envelope ->
        pipelineEventFactories[envelope.type]?.invoke(envelope.content)
            ?: PipelineEvent.Unknown(envelope.type, envelope.content)
    }

private data class PipelineEnvelope(val type: String, val content: JsonElement?)

private fun decodePipelineEnvelope(json: Json, text: String): PipelineEnvelope? {
    val message = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
    val type =
        (message?.get("type") as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.content
    return type?.let { PipelineEnvelope(it, decodePipelineContent(json, message["content"])) }
}

private fun decodePipelineContent(json: Json, rawContent: JsonElement?): JsonElement? = when {
    rawContent == null || rawContent is JsonNull -> null

    rawContent is JsonPrimitive && rawContent.isString ->
        runCatching { json.parseToJsonElement(rawContent.content) }.getOrNull()

    else -> rawContent
}

private typealias PipelineEventFactory = (JsonElement?) -> PipelineEvent

private val pipelineEventFactories: Map<String, PipelineEventFactory> =
    mapOf(
        "friend-online" to { PipelineEvent.FriendOnline(it) },
        "friend-offline" to { PipelineEvent.FriendOffline(it) },
        "friend-active" to { PipelineEvent.FriendActive(it) },
        "friend-update" to { PipelineEvent.FriendUpdate(it) },
        "friend-location" to { PipelineEvent.FriendLocation(it) },
        "friend-add" to { PipelineEvent.FriendAdd(it) },
        "friend-delete" to { PipelineEvent.FriendDelete(it) },
        "user-update" to { PipelineEvent.UserUpdate(it) },
        "user-location" to { PipelineEvent.UserLocation(it) },
        "notification" to { PipelineEvent.Notification(it) },
        "notification-v2" to { PipelineEvent.NotificationV2(it) },
        "notification-v2-delete" to { PipelineEvent.NotificationV2Delete(it) },
        "notification-v2-update" to { PipelineEvent.NotificationV2Update(it) },
        "see-notification" to { PipelineEvent.SeeNotification(it) },
        "hide-notification" to { PipelineEvent.HideNotification(it) },
        "response-notification" to { PipelineEvent.ResponseNotification(it) },
        "clear-notification" to { PipelineEvent.ClearNotification },
        "group-joined" to { PipelineEvent.GroupJoined(it) },
        "group-left" to { PipelineEvent.GroupLeft(it) },
        "group-role-updated" to { PipelineEvent.GroupRoleUpdated(it) },
        "group-member-updated" to { PipelineEvent.GroupMemberUpdated(it) },
        "content-refresh" to { PipelineEvent.ContentRefresh(it) },
        "instance-closed" to { PipelineEvent.InstanceClosed(it) },
    )
