package com.vrcx.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.vrcx.android.MainActivity
import com.vrcx.android.R
import com.vrcx.android.data.model.FriendContext
import com.vrcx.android.data.preferences.VrcxPreferences
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.FeedRepository
import com.vrcx.android.data.repository.FriendRepository
import com.vrcx.android.data.repository.GalleryRepository
import com.vrcx.android.data.repository.GroupRepository
import com.vrcx.android.data.repository.InstanceRepository
import com.vrcx.android.data.repository.NotificationRepository
import com.vrcx.android.data.websocket.PipelineEvent
import com.vrcx.android.data.websocket.VRChatWebSocket
import com.vrcx.android.data.websocket.WebSocketState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    @Inject lateinit var notificationRepository: NotificationRepository
    @Inject lateinit var groupRepository: GroupRepository
    @Inject lateinit var galleryRepository: GalleryRepository
    @Inject lateinit var instanceRepository: InstanceRepository
    @Inject lateinit var json: Json
    @Inject lateinit var okHttpClient: OkHttpClient
    @Inject lateinit var preferences: VrcxPreferences

    @Volatile private var prefNotifyInvite = true
    @Volatile private var prefNotifyFriendRequest = true
    @Volatile private var notifyEnabledFriendIds: Set<String> = emptySet()

    private var webSocket: VRChatWebSocket? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var notificationHelper: NotificationHelper? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var currentAuthToken: String? = null
    private var isForegroundMode = false
    private var startupJob: Job? = null

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
                startupJob?.cancel()
                startupJob = null
                webSocket?.disconnect()
                webSocket = null
                stopSelf()
            }
        }
        return if (isForegroundMode) START_STICKY else START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
            fgsType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC != 0
        ) {
            Log.w(TAG, "Foreground service dataSync timeout reached, stopping service")
            webSocket?.disconnect()
            webSocket = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
    }

    private fun startWebSocket(foreground: Boolean = true) {
        if (foreground) {
            // Must call startForeground immediately to avoid crash on Android 12+
            startForeground(NOTIFICATION_ID, createServiceNotification())
        }

        // Prevent duplicate connections
        if (webSocket != null || startupJob?.isActive == true) return

        startupJob = serviceScope.launch {
            try {
                if (!authRepository.ensureSessionReady()) {
                    if (foreground) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    }
                    stopSelf()
                    return@launch
                }

                val token = authRepository.authToken ?: run {
                    if (foreground) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    }
                    stopSelf()
                    return@launch
                }
                currentAuthToken = token

                if (foreground) {
                    acquireWakeLock()
                }

                // Set owner user ID for feed entries and group events
                val userId = authRepository.currentUser?.id ?: ""
                friendRepository.ownerUserId = userId
                groupRepository.ownerUserId = userId

                runCatching {
                    friendRepository.loadFriendsList()
                }.onFailure { error ->
                    Log.w(TAG, "Failed to preload friends list", error)
                }

                // Observe global notification preferences (invites + friend requests)
                serviceScope.launch {
                    combine(
                        preferences.notifyInvite,
                        preferences.notifyFriendRequest,
                    ) { invite: Boolean, friendReq: Boolean ->
                        prefNotifyInvite = invite
                        prefNotifyFriendRequest = friendReq
                    }.collect {}
                }

                // Observe per-friend notification enabled set
                serviceScope.launch {
                    friendRepository.observeNotifyEnabledIds(userId).collect { ids ->
                        notifyEnabledFriendIds = ids
                    }
                }

                // Dispatch offline notifications after 5-second confirmation delay
                serviceScope.launch {
                    friendRepository.confirmedOfflineEvents.collect { (offlineUserId, name) ->
                        if (offlineUserId in notifyEnabledFriendIds) {
                            notificationHelper?.notifyFriendOffline(name)
                        }
                    }
                }

                webSocket = VRChatWebSocket(json, okHttpClient).also { ws ->
                    ws.connect(token)
                    // Route WebSocket events to repositories + notifications
                    serviceScope.launch {
                        ws.events.collect { event ->
                            val previousFriend = previousFriendFor(event)
                            friendRepository.handleEvent(event)
                            notificationRepository.handleEvent(event)
                            authRepository.handleEvent(event)
                            groupRepository.handleEvent(event)
                            handleInstanceAndContentEvents(event)
                            dispatchNotification(event, previousFriend)
                        }
                    }
                }

                registerNetworkCallback()
            } finally {
                startupJob = null
            }
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
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
        startupJob = null
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

    private fun handleInstanceAndContentEvents(event: PipelineEvent) {
        when (event) {
            is PipelineEvent.ContentRefresh -> {
                val contentType = event.content?.jsonObject?.get("contentType")?.jsonPrimitive?.content ?: return
                val userId = authRepository.currentUser?.id ?: return
                serviceScope.launch { galleryRepository.handleContentRefresh(contentType, userId) }
            }
            is PipelineEvent.InstanceQueueJoined, is PipelineEvent.InstanceQueuePosition -> {
                val obj = event.content?.jsonObject ?: return
                val loc = obj["instanceLocation"]?.jsonPrimitive?.content ?: return
                val pos = obj["position"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val size = obj["queueSize"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                instanceRepository.handleQueueUpdate(loc, pos, size)
            }
            is PipelineEvent.InstanceQueueReady -> {
                val loc = event.content?.jsonObject?.get("instanceLocation")?.jsonPrimitive?.content ?: return
                instanceRepository.handleQueueReady(loc)
            }
            is PipelineEvent.InstanceQueueLeft -> {
                val loc = event.content?.jsonObject?.get("instanceLocation")?.jsonPrimitive?.content ?: return
                instanceRepository.handleQueueLeft(loc)
            }
            is PipelineEvent.InstanceClosed -> {
                val loc = event.content?.jsonObject?.get("instanceLocation")?.jsonPrimitive?.content
                Log.d(TAG, "Instance closed: $loc")
            }
            else -> {}
        }
    }

    private fun previousFriendFor(event: PipelineEvent): FriendContext? {
        val content = when (event) {
            is PipelineEvent.FriendUpdate -> event.content?.jsonObject
            is PipelineEvent.FriendLocation -> event.content?.jsonObject
            else -> null
        } ?: return null
        val userId = content["userId"]?.jsonPrimitive?.content ?: return null
        return friendRepository.friends.value[userId]
    }

    private fun dispatchNotification(event: PipelineEvent, previousFriend: FriendContext? = null) {
        val helper = notificationHelper ?: return
        when (event) {
            is PipelineEvent.FriendOnline -> {
                val content = event.content?.jsonObject ?: return
                val userId = content["userId"]?.jsonPrimitive?.content ?: return
                if (userId !in notifyEnabledFriendIds) return
                val name = content["user"]?.jsonObject?.get("displayName")?.jsonPrimitive?.content
                    ?: friendRepository.friends.value[userId]?.name
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
            is PipelineEvent.NotificationV2 -> {
                val content = event.content?.jsonObject ?: return
                val type = content["type"]?.jsonPrimitive?.content
                val sender = content["senderUsername"]?.jsonPrimitive?.content ?: "Someone"
                val title = content["title"]?.jsonPrimitive?.content.orEmpty()
                val message = content["message"]?.jsonPrimitive?.content.orEmpty()
                when (type) {
                    "friendRequest" -> if (prefNotifyFriendRequest) helper.notifyFriendRequest(sender)
                    "invite", "requestInvite" -> if (prefNotifyInvite) helper.notifyInvite(sender)
                    else -> helper.notifyGeneral(
                        title = title.ifBlank { sender },
                        text = message.ifBlank { type ?: "Notification" },
                    )
                }
            }
            is PipelineEvent.FriendLocation -> {
                val content = event.content?.jsonObject ?: return
                val userId = content["userId"]?.jsonPrimitive?.content ?: return
                if (userId !in notifyEnabledFriendIds) return
                val location = content["location"]?.jsonPrimitive?.content ?: return
                if (previousFriend?.ref?.location == location) return
                if (location == "offline" || location == "private" || location.isEmpty()) return
                val name = content["user"]?.jsonObject?.get("displayName")?.jsonPrimitive?.content
                    ?: friendRepository.friends.value[userId]?.name
                    ?: return
                val worldName = content["world"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: ""
                helper.notifyFriendLocation(name, worldName)
            }
            is PipelineEvent.FriendUpdate -> {
                val content = event.content?.jsonObject ?: return
                val userId = content["userId"]?.jsonPrimitive?.content ?: return
                if (userId !in notifyEnabledFriendIds) return
                val newStatus = content["user"]?.jsonObject?.get("status")?.jsonPrimitive?.content ?: return
                val previous = previousFriend?.ref ?: return
                if (newStatus != previous.status) {
                    val name = content["user"]?.jsonObject?.get("displayName")?.jsonPrimitive?.content ?: previous.displayName
                    helper.notifyFriendStatusChange(name, newStatus)
                }
            }
            is PipelineEvent.InstanceClosed -> {
                val location = event.content?.jsonObject?.get("instanceLocation")?.jsonPrimitive?.content.orEmpty()
                helper.notifyGeneral(
                    title = "Instance Closed",
                    text = location.ifBlank { "A queued instance closed" },
                )
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
        private const val SERVICE_LOG_TAG = "WebSocketForegroundSvc"

        fun start(context: Context): Boolean {
            val intent = Intent(context, WebSocketForegroundService::class.java).apply {
                action = ACTION_START
            }
            return runCatching {
                context.startForegroundService(intent)
                true
            }.getOrElse {
                Log.w(SERVICE_LOG_TAG, "Unable to start foreground websocket service", it)
                false
            }
        }

        fun startNonForeground(context: Context): Boolean {
            val intent = Intent(context, WebSocketForegroundService::class.java).apply {
                action = ACTION_START_NON_FOREGROUND
            }
            return runCatching {
                context.startService(intent)
                true
            }.getOrElse {
                Log.w(SERVICE_LOG_TAG, "Unable to start non-foreground websocket service", it)
                false
            }
        }

        fun stop(context: Context): Boolean {
            return runCatching {
                context.stopService(Intent(context, WebSocketForegroundService::class.java))
            }.getOrElse {
                Log.w(SERVICE_LOG_TAG, "Unable to stop websocket service", it)
                false
            }
        }
    }
}
