package com.vrcx.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.vrcx.android.MainActivity
import com.vrcx.android.R
import com.vrcx.android.data.preferences.VrcxPreferences
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.FeedRepository
import com.vrcx.android.data.repository.FriendRepository
import com.vrcx.android.data.websocket.PipelineEvent
import com.vrcx.android.data.websocket.VRChatWebSocket
import com.vrcx.android.data.websocket.WebSocketState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

@AndroidEntryPoint
class WebSocketForegroundService : Service() {
    private val TAG = "WebSocketForegroundService"

    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var friendRepository: FriendRepository
    @Inject lateinit var feedRepository: FeedRepository
    @Inject lateinit var json: Json
    @Inject lateinit var okHttpClient: OkHttpClient
    @Inject lateinit var preferences: VrcxPreferences

    @Volatile private var prefNotifyOnline = true
    @Volatile private var prefNotifyOffline = false
    @Volatile private var prefNotifyInvite = true
    @Volatile private var prefNotifyFriendRequest = true

    private var webSocket: VRChatWebSocket? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var notificationHelper: NotificationHelper? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var currentAuthToken: String? = null
    private var isForegroundMode = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        notificationHelper = NotificationHelper(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                isForegroundMode = true
                startWebSocket(foreground = true)
            }
            ACTION_START_NON_FOREGROUND -> {
                isForegroundMode = false
                startWebSocket(foreground = false)
            }
            ACTION_STOP -> {
                webSocket?.disconnect()
                webSocket = null
                stopSelf()
            }
        }
        return if (isForegroundMode) START_STICKY else START_NOT_STICKY
    }

    private fun startWebSocket(foreground: Boolean = true) {
        if (foreground) {
            // Must call startForeground immediately to avoid crash on Android 12+
            startForeground(NOTIFICATION_ID, createServiceNotification())
        }

        // Prevent duplicate connections
        if (webSocket != null) return

        val token = authRepository.authToken ?: run {
            stopSelf()
            return
        }
        currentAuthToken = token

        if (foreground) {
            acquireWakeLock()
        }

        // Set owner user ID for feed entries
        friendRepository.ownerUserId = authRepository.currentUser?.id ?: ""

        // Observe notification preferences
        serviceScope.launch {
            combine(
                preferences.notifyFriendOnline,
                preferences.notifyFriendOffline,
                preferences.notifyInvite,
                preferences.notifyFriendRequest,
            ) { online, offline, invite, friendReq ->
                prefNotifyOnline = online
                prefNotifyOffline = offline
                prefNotifyInvite = invite
                prefNotifyFriendRequest = friendReq
            }.collect {}
        }

        // Dispatch offline notifications after 5-second confirmation delay
        serviceScope.launch {
            friendRepository.confirmedOfflineEvents.collect { (_, name) ->
                if (prefNotifyOffline) {
                    notificationHelper?.notifyFriendOffline(name)
                }
            }
        }

        webSocket = VRChatWebSocket(json, okHttpClient).also { ws ->
            ws.connect(token)
            // Route WebSocket events to repositories + notifications
            serviceScope.launch {
                ws.events.collect { event ->
                    friendRepository.handleEvent(event)
                    dispatchNotification(event)
                }
            }
        }

        registerNetworkCallback()
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network available")
                val ws = webSocket ?: return
                val token = currentAuthToken ?: return
                if (ws.state.value != WebSocketState.CONNECTED) {
                    ws.reconnectNow(token)
                }
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "Network lost")
            }
        }
        networkCallback = callback
        cm.registerDefaultNetworkCallback(callback)
    }

    override fun onDestroy() {
        networkCallback?.let {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(it)
            networkCallback = null
        }
        serviceScope.cancel()
        webSocket?.disconnect()
        webSocket = null
        releaseWakeLock()
        super.onDestroy()
    }

    private fun createServiceNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, CHANNEL_SERVICE)
            .setContentTitle("VRCX")
            .setContentText("Connected to VRChat")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        listOf(
            NotificationChannel(CHANNEL_SERVICE, "Background Service", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(CHANNEL_FRIEND_ONLINE, "Friend Online", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CHANNEL_FRIEND_OFFLINE, "Friend Offline", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(CHANNEL_INVITES, "Invites", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CHANNEL_FRIEND_REQUEST, "Friend Requests", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CHANNEL_GENERAL, "General", NotificationManager.IMPORTANCE_DEFAULT),
        ).forEach { manager.createNotificationChannel(it) }
    }

    private fun dispatchNotification(event: PipelineEvent) {
        val helper = notificationHelper ?: return
        when (event) {
            is PipelineEvent.FriendOnline -> {
                if (!prefNotifyOnline) return
                val name = event.content?.jsonObject?.get("user")?.jsonObject?.get("displayName")?.jsonPrimitive?.content
                    ?: friendRepository.friends.value[event.content?.jsonObject?.get("userId")?.jsonPrimitive?.content]?.name
                    ?: return
                helper.notifyFriendOnline(name)
            }
            is PipelineEvent.Notification -> {
                val type = event.content?.jsonObject?.get("type")?.jsonPrimitive?.content
                val sender = event.content?.jsonObject?.get("senderUsername")?.jsonPrimitive?.content ?: "Someone"
                when (type) {
                    "friendRequest" -> if (prefNotifyFriendRequest) helper.notifyFriendRequest(sender)
                    "invite", "requestInvite" -> if (prefNotifyInvite) helper.notifyInvite(sender)
                }
            }
            else -> {}
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vrcx:websocket").apply {
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        const val ACTION_START = "com.vrcx.android.START_WEBSOCKET"
        const val ACTION_START_NON_FOREGROUND = "com.vrcx.android.START_WEBSOCKET_NON_FOREGROUND"
        const val ACTION_STOP = "com.vrcx.android.STOP_WEBSOCKET"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_SERVICE = "vrcx_service"
        const val CHANNEL_FRIEND_ONLINE = "vrcx_friend_online"
        const val CHANNEL_FRIEND_OFFLINE = "vrcx_friend_offline"
        const val CHANNEL_INVITES = "vrcx_invites"
        const val CHANNEL_FRIEND_REQUEST = "vrcx_friend_request"
        const val CHANNEL_GENERAL = "vrcx_general"

        fun start(context: Context) {
            val intent = Intent(context, WebSocketForegroundService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun startNonForeground(context: Context) {
            val intent = Intent(context, WebSocketForegroundService::class.java).apply {
                action = ACTION_START_NON_FOREGROUND
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, WebSocketForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
