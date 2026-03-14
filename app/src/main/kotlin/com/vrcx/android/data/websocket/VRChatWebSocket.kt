package com.vrcx.android.data.websocket

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

enum class WebSocketState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

class VRChatWebSocket(
    private val json: Json,
    baseClient: OkHttpClient,
) {
    private val TAG = "VRChatWebSocket"
    private val WEBSOCKET_URL = "wss://pipeline.vrchat.cloud"
    private val BASE_RECONNECT_DELAY_MS = 5000L
    private val MAX_RECONNECT_DELAY_MS = 300_000L // 5 min cap
    private val MAX_RECONNECT_ATTEMPTS = 50
    private var reconnectAttempt = 0

    private val _events = MutableSharedFlow<PipelineEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<PipelineEvent> = _events.asSharedFlow()

    private val _state = MutableStateFlow(WebSocketState.DISCONNECTED)
    val state: StateFlow<WebSocketState> = _state.asStateFlow()

    private var webSocket: WebSocket? = null
    private var shouldReconnect = false
    private var lastMessage: String? = null
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Use the authenticated client (with cookies) but with no read timeout for WebSocket
    private val client = baseClient.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun connect(authToken: String) {
        shouldReconnect = true
        _state.value = WebSocketState.CONNECTING

        val request = Request.Builder()
            .url("$WEBSOCKET_URL/?auth=$authToken")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                reconnectAttempt = 0
                _state.value = WebSocketState.CONNECTED
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Duplicate filtering
                if (text == lastMessage) return
                lastMessage = text
                parseAndEmit(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code $reason")
                _state.value = WebSocketState.DISCONNECTED
                attemptReconnect(authToken)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                _state.value = WebSocketState.DISCONNECTED
                attemptReconnect(authToken)
            }
        })
    }

    fun disconnect() {
        shouldReconnect = false
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        _state.value = WebSocketState.DISCONNECTED
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    fun reconnectNow(authToken: String) {
        shouldReconnect = false
        webSocket?.close(1000, "Reconnecting")
        webSocket = null
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        reconnectAttempt = 0
        connect(authToken)
    }

    private fun attemptReconnect(authToken: String) {
        if (!shouldReconnect) return
        reconnectAttempt++
        if (reconnectAttempt > MAX_RECONNECT_ATTEMPTS) {
            Log.e(TAG, "Max reconnect attempts ($MAX_RECONNECT_ATTEMPTS) reached, giving up")
            _state.value = WebSocketState.DISCONNECTED
            return
        }
        _state.value = WebSocketState.RECONNECTING
        val delayMs = minOf(
            BASE_RECONNECT_DELAY_MS * (1L shl (reconnectAttempt - 1).coerceAtMost(17)),
            MAX_RECONNECT_DELAY_MS,
        ) + (0..2000L).random()
        Log.d(TAG, "Reconnect attempt $reconnectAttempt in ${delayMs}ms")
        scope.launch {
            delay(delayMs)
            if (shouldReconnect) {
                connect(authToken)
            }
        }
    }

    private fun parseAndEmit(text: String) {
        try {
            val msg = json.parseToJsonElement(text).jsonObject
            val type = msg["type"]?.jsonPrimitive?.content ?: return
            // content can be a JSON string (needs double-parse) or a JSON object directly
            val rawContent = msg["content"]
            val content: JsonElement? = when {
                rawContent == null -> null
                rawContent is kotlinx.serialization.json.JsonPrimitive && rawContent.isString -> {
                    try { json.parseToJsonElement(rawContent.content) } catch (_: Exception) { null }
                }
                else -> rawContent // already a JsonObject/JsonArray
            }

            val event = when (type) {
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
                "group-joined" -> PipelineEvent.GroupJoined(content)
                "group-left" -> PipelineEvent.GroupLeft(content)
                "group-role-updated" -> PipelineEvent.GroupRoleUpdated(content)
                "group-member-updated" -> PipelineEvent.GroupMemberUpdated(content)
                "instance-queue-joined" -> PipelineEvent.InstanceQueueJoined(content)
                "instance-queue-position" -> PipelineEvent.InstanceQueuePosition(content)
                "instance-queue-ready" -> PipelineEvent.InstanceQueueReady(content)
                "instance-queue-left" -> PipelineEvent.InstanceQueueLeft(content)
                "content-refresh" -> PipelineEvent.ContentRefresh(content)
                "instance-closed" -> PipelineEvent.InstanceClosed(content)
                else -> PipelineEvent.Unknown(type, content)
            }

            scope.launch {
                _events.emit(event)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse WebSocket message: ${e.message}")
        }
    }
}
