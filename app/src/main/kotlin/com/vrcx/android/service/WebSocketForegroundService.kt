package com.vrcx.android.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.vrcx.android.MainActivity
import com.vrcx.android.R
import com.vrcx.android.data.model.FriendTransition
import com.vrcx.android.data.preferences.VrcxPreferences
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.FriendRepository
import com.vrcx.android.data.repository.GalleryRepository
import com.vrcx.android.data.repository.GroupRepository
import com.vrcx.android.data.repository.NotificationKind
import com.vrcx.android.data.repository.NotificationRepository
import com.vrcx.android.data.websocket.PipelineEvent
import com.vrcx.android.data.websocket.VRChatWebSocket
import com.vrcx.android.data.websocket.WebSocketState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CancellationException
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
import javax.inject.Named

@AndroidEntryPoint
class WebSocketForegroundService : Service() {
    private val TAG = "WebSocketForegroundService"

    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var friendRepository: FriendRepository
    @Inject lateinit var notificationRepository: NotificationRepository
    @Inject lateinit var groupRepository: GroupRepository
    @Inject lateinit var galleryRepository: GalleryRepository
    @Inject lateinit var json: Json
    @Inject @Named("webSocketOkHttpClient") lateinit var okHttpClient: OkHttpClient
    @Inject lateinit var preferences: VrcxPreferences

    @Volatile private var prefNotifyInvite = true
    @Volatile private var prefNotifyFriendRequest = true
    @Volatile private var notifyEnabledFriendIds: Set<String> = emptySet()

    private var webSocket: VRChatWebSocket? = null
    private var notificationHelper: NotificationHelper? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var activeNetwork: Network? = null
    private var currentAuthToken: String? = null
    private var isForegroundMode = false
    private var startupJob: Job? = null
    private val serviceStatePreferences by lazy {
        getSharedPreferences(SERVICE_STATE_PREFERENCES, Context.MODE_PRIVATE)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // NotificationHelper's init registers the shared notification channels.
        notificationHelper = NotificationHelper(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                setRequestedMode(REQUESTED_MODE_FOREGROUND)
                clearTimeoutState()
                isForegroundMode = true
                startWebSocket(foreground = true)
            }
            ACTION_START_NON_FOREGROUND -> {
                setRequestedMode(REQUESTED_MODE_NON_FOREGROUND)
                isForegroundMode = false
                startWebSocket(foreground = false)
            }
            null -> {
                if (requestedMode() == REQUESTED_MODE_FOREGROUND) {
                    isForegroundMode = true
                    startWebSocket(foreground = true)
                } else {
                    isForegroundMode = false
                    stopSelf(startId)
                }
            }
        }
        return if (isForegroundMode) START_STICKY else START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
            fgsType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC != 0
        ) {
            Log.w(TAG, "Foreground service dataSync timeout reached; waiting for foreground recovery")
            serviceStatePreferences.edit()
                .putBoolean(KEY_STOPPED_BY_TIMEOUT, true)
                .putInt(KEY_REQUESTED_MODE, REQUESTED_MODE_NONE)
                .commit()
            notificationHelper?.notifyServiceReconnectRequired()
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

                // Set owner user ID for feed entries and group events
                val userId = authRepository.currentUser?.id ?: ""
                friendRepository.ownerUserId = userId
                groupRepository.ownerUserId = userId

                try {
                    friendRepository.loadFriendsList()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
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

                // Map friend online/location/status transitions to notifications.
                // FriendRepository resolves userId + display name (tolerating the
                // lowercase "userid" key) and the location/status comparisons, so
                // the service no longer re-parses the raw event payload.
                serviceScope.launch {
                    friendRepository.friendTransitions.collect { transition ->
                        if (transition.userId !in notifyEnabledFriendIds) return@collect
                        val helper = notificationHelper ?: return@collect
                        when (transition) {
                            is FriendTransition.CameOnline ->
                                helper.notifyFriendOnline(transition.displayName)
                            is FriendTransition.CameOffline ->
                                helper.notifyFriendOffline(transition.displayName)
                            is FriendTransition.ChangedLocation ->
                                helper.notifyFriendLocation(transition.displayName, transition.worldName)
                            is FriendTransition.ChangedStatus ->
                                helper.notifyFriendStatusChange(transition.displayName, transition.status)
                        }
                    }
                }

                webSocket = VRChatWebSocket(json, okHttpClient).also { ws ->
                    ws.connect(token)
                    // Route WebSocket events to repositories + notifications
                    serviceScope.launch {
                        ws.events.collect { event ->
                            friendRepository.handleEvent(event)
                            notificationRepository.handleEvent(event)
                            authRepository.handleEvent(event)
                            groupRepository.handleEvent(event)
                            handleContentRefresh(event)
                            dispatchNotification(event)
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
                val previousNetwork = activeNetwork
                activeNetwork = network
                val ws = webSocket ?: return
                val token = currentAuthToken ?: return
                val networkWasReplaced = previousNetwork != null && previousNetwork != network
                if (networkWasReplaced || ws.state.value == WebSocketState.DISCONNECTED) {
                    ws.reconnectNow(token)
                }
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "Network lost")
                if (activeNetwork != network) return
                activeNetwork = null
                val ws = webSocket ?: return
                val token = currentAuthToken ?: return
                ws.reconnectNow(token)
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
        activeNetwork = null
        serviceScope.cancel()
        startupJob = null
        webSocket?.disconnect()
        webSocket = null
        setRequestedMode(REQUESTED_MODE_NONE)
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

    private fun handleContentRefresh(event: PipelineEvent) {
        if (event !is PipelineEvent.ContentRefresh) return
        val contentType = event.content?.jsonObject?.get("contentType")?.jsonPrimitive?.content ?: return
        val userId = authRepository.currentUser?.id ?: return
        serviceScope.launch { galleryRepository.handleContentRefresh(contentType, userId) }
    }

    private fun dispatchNotification(event: PipelineEvent) {
        val helper = notificationHelper ?: return
        when (event) {
            is PipelineEvent.Notification -> {
                val type = event.content?.jsonObject?.get("type")?.jsonPrimitive?.content.orEmpty()
                val sender = event.content?.jsonObject?.get("senderUsername")?.jsonPrimitive?.content ?: "Someone"
                when (NotificationKind.fromType(type)) {
                    NotificationKind.FRIEND_REQUEST -> if (prefNotifyFriendRequest) helper.notifyFriendRequest(sender)
                    NotificationKind.INVITE, NotificationKind.REQUEST_INVITE ->
                        if (prefNotifyInvite) helper.notifyInvite(sender)
                    NotificationKind.OTHER -> {}
                }
            }
            is PipelineEvent.NotificationV2 -> {
                val content = event.content?.jsonObject ?: return
                val type = content["type"]?.jsonPrimitive?.content.orEmpty()
                val sender = content["senderUsername"]?.jsonPrimitive?.content ?: "Someone"
                val title = content["title"]?.jsonPrimitive?.content.orEmpty()
                val message = content["message"]?.jsonPrimitive?.content.orEmpty()
                when (NotificationKind.fromType(type)) {
                    NotificationKind.FRIEND_REQUEST -> if (prefNotifyFriendRequest) helper.notifyFriendRequest(sender)
                    NotificationKind.INVITE, NotificationKind.REQUEST_INVITE ->
                        if (prefNotifyInvite) helper.notifyInvite(sender)
                    NotificationKind.OTHER -> helper.notifyGeneral(
                        title = title.ifBlank { sender },
                        text = message.ifBlank { type.ifBlank { "Notification" } },
                    )
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

    private fun requestedMode(): Int = serviceStatePreferences.getInt(
        KEY_REQUESTED_MODE,
        REQUESTED_MODE_NONE,
    )

    private fun setRequestedMode(mode: Int) {
        serviceStatePreferences.edit().putInt(KEY_REQUESTED_MODE, mode).commit()
    }

    private fun clearTimeoutState() {
        serviceStatePreferences.edit().putBoolean(KEY_STOPPED_BY_TIMEOUT, false).apply()
        notificationHelper?.cancelServiceReconnectRequired()
    }

    companion object {
        private const val ACTION_START = "com.vrcx.android.START_WEBSOCKET"
        private const val ACTION_START_NON_FOREGROUND = "com.vrcx.android.START_WEBSOCKET_NON_FOREGROUND"
        private const val NOTIFICATION_ID = 1
        const val CHANNEL_SERVICE = "vrcx_service"
        const val CHANNEL_FRIEND_ONLINE = "vrcx_friend_online"
        const val CHANNEL_FRIEND_OFFLINE = "vrcx_friend_offline"
        const val CHANNEL_INVITES = "vrcx_invites"
        const val CHANNEL_FRIEND_REQUEST = "vrcx_friend_request"
        const val CHANNEL_GENERAL = "vrcx_general"
        private const val SERVICE_LOG_TAG = "WebSocketForegroundSvc"
        private const val SERVICE_STATE_PREFERENCES = "websocket_service_state"
        private const val KEY_REQUESTED_MODE = "requested_mode"
        private const val KEY_STOPPED_BY_TIMEOUT = "stopped_by_timeout"
        private const val REQUESTED_MODE_NONE = 0
        private const val REQUESTED_MODE_FOREGROUND = 1
        private const val REQUESTED_MODE_NON_FOREGROUND = 2

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

        fun restartAfterTimeoutIfNeeded(context: Context): Boolean {
            val hasTimedOut = context
                .getSharedPreferences(SERVICE_STATE_PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(KEY_STOPPED_BY_TIMEOUT, false)
            if (!hasTimedOut) return false
            val started = start(context)
            if (started) {
                context.getSharedPreferences(SERVICE_STATE_PREFERENCES, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_STOPPED_BY_TIMEOUT, false)
                    .apply()
                NotificationHelper(context).cancelServiceReconnectRequired()
            }
            return started
        }
    }
}
