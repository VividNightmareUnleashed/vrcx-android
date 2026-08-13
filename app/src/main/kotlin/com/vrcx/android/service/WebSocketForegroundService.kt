package com.vrcx.android.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
import com.vrcx.android.data.repository.AccountChangedException
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.data.repository.FriendRepository
import com.vrcx.android.data.repository.GalleryRepository
import com.vrcx.android.data.repository.GroupRepository
import com.vrcx.android.data.repository.NotificationKind
import com.vrcx.android.data.repository.NotificationRepository
import com.vrcx.android.data.websocket.PipelineEvent
import com.vrcx.android.data.websocket.PipelineOkHttpClient
import com.vrcx.android.data.websocket.VRChatWebSocket
import com.vrcx.android.data.websocket.shouldForceReconnect
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

@AndroidEntryPoint
class WebSocketForegroundService : Service() {

    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var friendRepository: FriendRepository
    @Inject lateinit var notificationRepository: NotificationRepository
    @Inject lateinit var groupRepository: GroupRepository
    @Inject lateinit var galleryRepository: GalleryRepository
    @Inject lateinit var json: Json
    @Inject lateinit var okHttpClient: PipelineOkHttpClient
    @Inject lateinit var preferences: VrcxPreferences

    @Volatile private var prefNotifyInvite = true
    @Volatile private var prefNotifyFriendRequest = true
    @Volatile private var prefNotifyGeneral = true
    @Volatile private var notifyEnabledFriendIds: Set<String> = emptySet()

    // Written on serviceScope, read from the ConnectivityManager callback thread
    // and from onDestroy on the main thread. Observing a stale null there drops
    // the reconnect after a network transition, or skips the socket teardown.
    @Volatile private var webSocket: VRChatWebSocket? = null
    @Volatile private var currentAuthToken: String? = null
    private var notificationHelper: NotificationHelper? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var activeNetwork: Network? = null
    private var startupJob: Job? = null
    @Volatile private var reauthJob: Job? = null
    @Volatile private var handshakeRejections = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // NotificationHelper's init registers the shared notification channels.
        notificationHelper = NotificationHelper(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = when (intent?.action) {
            ACTION_START -> ServiceMode.FOREGROUND
            ACTION_START_NON_FOREGROUND -> ServiceMode.NON_FOREGROUND
            // A START_STICKY redelivery carries a null action. Only foreground
            // mode asks for one, so anything else is a restart to decline.
            else -> serviceMode(this).takeIf { it == ServiceMode.FOREGROUND }
        }
        if (mode == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        applyMode(mode)
        return if (mode == ServiceMode.FOREGROUND) START_STICKY else START_NOT_STICKY
    }

    /**
     * Move the service to the mode its owner just declared, without dropping a
     * live socket. Both directions are real transitions: promoting runs
     * `startForeground`, demoting has to take the ongoing notification and the
     * foreground-service state back down or a user who turned the background
     * service off keeps both.
     */
    private fun applyMode(mode: ServiceMode) {
        val previous = serviceMode(this)
        setServiceMode(this, mode)
        // Declaring a mode supersedes any pending Android 15 recovery.
        notificationHelper?.cancelServiceReconnectRequired()
        if (mode == ServiceMode.FOREGROUND) {
            // Must call startForeground immediately to avoid crash on Android 12+
            startForeground(NOTIFICATION_ID, createServiceNotification())
        } else if (previous == ServiceMode.FOREGROUND) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        startWebSocket()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
            fgsType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC != 0
        ) {
            Log.w(SERVICE_LOG_TAG, "Foreground service dataSync timeout reached; waiting for foreground recovery")
            // Synchronous on purpose: the process can be torn down as soon as
            // this returns, and the recorded mode is the only thing that brings
            // the socket back on the next activity start.
            setServiceMode(this, ServiceMode.TIMED_OUT, synchronous = true)
            notificationHelper?.notifyServiceReconnectRequired()
            webSocket?.disconnect()
            webSocket = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
    }

    private fun startWebSocket() {
        // Prevent duplicate connections
        if (webSocket != null || startupJob?.isActive == true) return
        startupJob = serviceScope.launch { runStartup() }
    }

    private suspend fun runStartup() {
        try {
            if (!awaitSession()) return
            val token = authRepository.authToken ?: run {
                stopWithCleanup()
                return
            }
            currentAuthToken = token

            val userId = authRepository.currentUser?.id ?: ""

            // The service owns its own lifecycle, so the session ending — an
            // explicit sign-out or a 401 AuthRepository confirmed — is what takes
            // the socket and the ongoing notification down. The data layer does
            // not reach into the service to do it.
            serviceScope.launch {
                stopWhenSessionEnds(authRepository.authState) { stopWithCleanup() }
            }

            try {
                friendRepository.loadFriendsList()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.w(SERVICE_LOG_TAG, "Failed to preload friends list", error)
            }

            // Observe global notification preferences (invites + friend requests
            // + everything this app doesn't model)
            serviceScope.launch {
                combine(
                    preferences.notifyInvite,
                    preferences.notifyFriendRequest,
                    preferences.notifyGeneral,
                ) { invite: Boolean, friendReq: Boolean, general: Boolean ->
                    prefNotifyInvite = invite
                    prefNotifyFriendRequest = friendReq
                    prefNotifyGeneral = general
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
                    notifyFriendTransition(helper, transition)
                }
            }

            webSocket = VRChatWebSocket(json, okHttpClient, ::refreshTokenAndReconnect).also { ws ->
                // Subscribe before connecting. The event flow has no replay, so
                // anything VRChat pushes between the handshake completing and
                // the collector arriving would be dropped without a trace —
                // including the burst it sends immediately after connect.
                val subscribed = CompletableDeferred<Unit>()
                serviceScope.launch {
                    ws.events
                        .onSubscription { subscribed.complete(Unit) }
                        .collect { event -> routeEvent(event) }
                }
                subscribed.await()
                ws.connect(token)
            }

            registerNetworkCallback()
        } catch (cancellation: CancellationException) {
            // onDestroy cancels serviceScope; that is a normal shutdown and must
            // not be answered with more teardown.
            throw cancellation
        } catch (error: Exception) {
            // startForeground has already run by this point, so leaving the job
            // dead would strand an "ongoing" notification over no connection.
            Log.e(SERVICE_LOG_TAG, "WebSocket service startup failed", error)
            stopWithCleanup()
        } finally {
            startupJob = null
        }
    }

    /**
     * Wait until the stored session is usable, or until it is clear that waiting
     * is pointless.
     *
     * Resuming routinely fails on a cold start while the radio is still waking
     * up — which is exactly why AuthRepository keeps the cookies rather than
     * discarding them. Stopping the service there kills background presence for
     * the rest of the process's life, and nothing schedules another attempt.
     */
    private suspend fun awaitSession(): Boolean {
        var attempt = 0
        while (true) {
            if (authRepository.ensureSessionReady()) return true
            val decision = sessionStartupDecision(
                authState = authRepository.authState.value,
                hasResumableSession = authRepository.hasResumableSession(),
            )
            if (decision == SessionStartup.STOP) {
                stopWithCleanup()
                return false
            }
            attempt++
            Log.d(SERVICE_LOG_TAG, "Session not ready; retrying attempt $attempt")
            delay(sessionRetryDelayMs(attempt))
        }
    }

    /**
     * VRChat refused the pipeline token itself. The service captures the token
     * once per start, so nothing else would ever fetch another one and the
     * socket would report RECONNECTING against a credential that can never
     * succeed. If the session is genuinely gone, the token request's own 401
     * ends it through AuthRepository and stops the service from under us.
     */
    private fun refreshTokenAndReconnect() {
        if (reauthJob?.isActive == true) return
        reauthJob = serviceScope.launch {
            handshakeRejections++
            // Back off first, so a persistently refused token cannot turn into
            // a request storm against the auth endpoints.
            delay(sessionRetryDelayMs(handshakeRejections))
            authRepository.fetchAuthToken()
            // Reconnect with whatever we have: a fetch that failed leaves the
            // old token, and letting the attempt happen keeps the rejection
            // loop alive rather than parking the socket for good.
            val token = authRepository.authToken ?: currentAuthToken ?: return@launch
            currentAuthToken = token
            webSocket?.reconnectNow(token)
        }
    }

    private fun stopWithCleanup() {
        if (serviceMode(this) == ServiceMode.FOREGROUND) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    private suspend fun routeEvent(event: PipelineEvent) {
        containPipelineFailure("friends") { friendRepository.handleEvent(event) }
        containPipelineFailure("notifications") { notificationRepository.handleEvent(event) }
        containPipelineFailure("session") { authRepository.handleEvent(event) }
        containPipelineFailure("groups") { groupRepository.handleEvent(event) }
        containPipelineFailure("gallery") { handleContentRefresh(event) }
        containPipelineFailure("system notifications") { dispatchNotification(event) }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(SERVICE_LOG_TAG, "Network available")
                val previousNetwork = activeNetwork
                activeNetwork = network
                val ws = webSocket ?: return
                val token = currentAuthToken ?: return
                val networkWasReplaced = previousNetwork != null && previousNetwork != network
                if (shouldForceReconnect(networkWasReplaced, ws.state.value)) {
                    ws.reconnectNow(token)
                }
            }

            override fun onLost(network: Network) {
                Log.d(SERVICE_LOG_TAG, "Network lost")
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
        // onTimeout stops the service, so onDestroy runs straight after it —
        // writing NONE here unconditionally would erase the recovery it just
        // recorded.
        if (serviceMode(this) != ServiceMode.TIMED_OUT) {
            setServiceMode(this, ServiceMode.NONE)
        }
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
        val contentType = event.contentObject()?.stringOrNull("contentType") ?: return
        val userId = authRepository.currentUser?.id ?: return
        // The boundary belongs inside the launch: a throw from the child would
        // otherwise reach the default handler long after routeEvent returned.
        serviceScope.launch {
            containPipelineFailure("gallery refresh") {
                galleryRepository.handleContentRefresh(contentType, userId)
            }
        }
    }

    private fun dispatchNotification(event: PipelineEvent) {
        val helper = notificationHelper ?: return
        dispatchNotification(helper, event, prefNotifyInvite, prefNotifyFriendRequest, prefNotifyGeneral)
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
        private const val SERVICE_STATE_PREFERENCES = "websocket_service_state"
        private const val KEY_SERVICE_MODE = "requested_mode"

        private fun statePreferences(context: Context): SharedPreferences =
            context.getSharedPreferences(SERVICE_STATE_PREFERENCES, Context.MODE_PRIVATE)

        internal fun serviceMode(context: Context): ServiceMode =
            ServiceMode.fromStored(statePreferences(context).getInt(KEY_SERVICE_MODE, ServiceMode.NONE.storedValue))

        internal fun setServiceMode(context: Context, mode: ServiceMode, synchronous: Boolean = false) {
            val editor = statePreferences(context).edit().putInt(KEY_SERVICE_MODE, mode.storedValue)
            if (synchronous) editor.commit() else editor.apply()
        }

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
            // Also clear the recorded mode: a service that already stopped itself
            // on an Android 15 timeout never reaches onDestroy, and its pending
            // recovery must not outlive an explicit sign-out. Sign-out teardown
            // has to finish even when a step of it cannot.
            runCatching { setServiceMode(context, ServiceMode.NONE) }
            return runCatching {
                context.stopService(Intent(context, WebSocketForegroundService::class.java))
            }.getOrElse {
                Log.w(SERVICE_LOG_TAG, "Unable to stop websocket service", it)
                false
            }
        }

        /**
         * Bring the socket back after an Android 15 dataSync timeout.
         *
         * TIMED_OUT is only reachable from FOREGROUND — a non-foreground service
         * is not a foreground service and cannot time out — so reaching here
         * means the user was logged in with the background service enabled.
         * Signing out and turning the preference off both rewrite the mode, so
         * a stale recovery cannot start a service the user opted out of. The
         * mode is cleared by the service's own start branch, not here, so a
         * start that never reaches the service leaves the recovery pending.
         */
        fun restartAfterTimeoutIfNeeded(context: Context): Boolean {
            if (serviceMode(context) != ServiceMode.TIMED_OUT) return false
            return start(context)
        }
    }
}

/**
 * What the service has been asked to be, as a single value.
 *
 * This used to be a persisted int, a persisted boolean and an in-memory
 * boolean, which made combinations like "not running, but timed out from
 * non-foreground mode" representable and left three places to keep in step.
 * The stored values are explicit so reordering the enum cannot reinterpret
 * what is already on disk.
 */
internal enum class ServiceMode(val storedValue: Int) {
    NONE(0),
    FOREGROUND(1),
    NON_FOREGROUND(2),
    TIMED_OUT(3);

    companion object {
        fun fromStored(value: Int): ServiceMode = values().firstOrNull { it.storedValue == value } ?: NONE
    }
}

/**
 * Turns a friend transition into its system notification.
 *
 * The transition already carries the friend's id, so the notification points at
 * that friend's screen — a "went online" the user taps has to land on the person
 * it names, not on whichever tab the app happens to start on.
 */
internal fun notifyFriendTransition(helper: NotificationHelper, transition: FriendTransition) {
    when (transition) {
        is FriendTransition.CameOnline ->
            helper.notifyFriendOnline(transition.displayName, transition.userId)
        is FriendTransition.CameOffline ->
            helper.notifyFriendOffline(transition.displayName, transition.userId)
        is FriendTransition.ChangedLocation ->
            helper.notifyFriendLocation(transition.displayName, transition.worldName, transition.userId)
        is FriendTransition.ChangedStatus ->
            helper.notifyFriendStatusChange(transition.displayName, transition.status, transition.userId)
    }
}

/**
 * Stop the service the moment the session ends.
 *
 * The lifecycle belongs to the service, not to the data layer: AuthRepository
 * publishes [AuthState.NotLoggedIn] and the socket, the ongoing notification and
 * this process's claim on the foreground go with it. Top-level so the rule can
 * be asserted without standing a Service up.
 */
internal suspend fun stopWhenSessionEnds(authState: Flow<AuthState>, stop: () -> Unit) {
    authState.collect { state -> if (state is AuthState.NotLoggedIn) stop() }
}

/**
 * Turns a pipeline frame into a system notification, gated by the three
 * notification preferences the app exposes.
 *
 * Top-level rather than a service method so the classification, the preference
 * gates and the deep-link target can be asserted without standing a Service up.
 */
internal fun dispatchNotification(
    helper: NotificationHelper,
    event: PipelineEvent,
    notifyInvite: Boolean,
    notifyFriendRequest: Boolean,
    notifyGeneral: Boolean,
) {
    when (event) {
        is PipelineEvent.Notification -> {
            val content = event.contentObject() ?: return
            val type = content.stringOrNull("type").orEmpty()
            val sender = content.stringOrNull("senderUsername") ?: "Someone"
            val senderId = content.stringOrNull("senderUserId")
            when (NotificationKind.fromType(type)) {
                NotificationKind.FRIEND_REQUEST ->
                    if (notifyFriendRequest) helper.notifyFriendRequest(sender, senderId)
                NotificationKind.INVITE, NotificationKind.REQUEST_INVITE ->
                    if (notifyInvite) helper.notifyInvite(sender, senderId)
                NotificationKind.OTHER -> {}
            }
        }
        is PipelineEvent.NotificationV2 -> {
            val content = event.contentObject() ?: return
            val type = content.stringOrNull("type").orEmpty()
            val sender = content.stringOrNull("senderUsername") ?: "Someone"
            val senderId = content.stringOrNull("senderUserId")
            val title = content.stringOrNull("title").orEmpty()
            val message = content.stringOrNull("message").orEmpty()
            when (NotificationKind.fromType(type)) {
                NotificationKind.FRIEND_REQUEST ->
                    if (notifyFriendRequest) helper.notifyFriendRequest(sender, senderId)
                NotificationKind.INVITE, NotificationKind.REQUEST_INVITE ->
                    if (notifyInvite) helper.notifyInvite(sender, senderId)
                // A type this app doesn't model still reaches the shade, so
                // whoever originated it chooses the text there. Give the user a
                // switch for the whole category, bound what does get through,
                // and don't echo the raw type back as the body.
                NotificationKind.OTHER ->
                    if (notifyGeneral) helper.notifyGeneral(
                        title = boundRemoteText(title.ifBlank { sender }),
                        text = boundRemoteText(message.ifBlank { "New notification" }),
                    )
            }
        }
        is PipelineEvent.InstanceClosed -> {
            val location = event.contentObject()?.stringOrNull("instanceLocation").orEmpty()
            helper.notifyGeneral(
                title = "Instance Closed",
                text = boundRemoteText(location.ifBlank { "A queued instance closed" }),
            )
        }
        else -> {}
    }
}

/**
 * One malformed frame costs one capability, not the process.
 *
 * VRChat returns several payload fields as more than one shape, so a frame the
 * app has never seen can throw out of a handler. The pipeline fan-out is a
 * single root coroutine with no exception handler, so that throw would reach
 * the thread's default handler and kill the process — and the pipeline has no
 * replay, so everything that arrives during the restart is lost for good.
 */
internal inline fun containPipelineFailure(capability: String, block: () -> Unit) {
    try {
        block()
    } catch (stale: AccountChangedException) {
        // Manufactured staleness, not real cancellation: the work belonged to an
        // account that has since been switched away from. Dropping it is the
        // point — the current account keeps being served.
        Log.d(SERVICE_LOG_TAG, "Discarded stale $capability work after an account change")
    } catch (cancellation: CancellationException) {
        // Genuine cancellation. Swallowing it would leave collectors running
        // after their scope is gone.
        throw cancellation
    } catch (error: Exception) {
        Log.w(SERVICE_LOG_TAG, "Pipeline event dropped by $capability", error)
    }
}

/**
 * One tag for the whole class. The statics used to log under a second, shorter
 * one, so `adb logcat -s WebSocketForegroundService` silently omitted every
 * start/stop failure — the class of failure that is hardest to reproduce.
 */
internal const val SERVICE_LOG_TAG = "WebSocketForegroundService"

internal enum class SessionStartup { RETRY, STOP }

/**
 * What to do when the session is not ready yet.
 *
 * A rejection from VRChat clears the stored cookie and a two-factor challenge
 * needs the user, so in both cases the socket has nothing to wait for. Anything
 * else — no radio yet, a timeout, a 5xx — proves nothing: AuthRepository keeps
 * the cookies precisely because the session may still be good, so the service
 * has to try again rather than stop until the user reopens the app.
 */
internal fun sessionStartupDecision(
    authState: AuthState,
    hasResumableSession: Boolean,
): SessionStartup = when {
    !hasResumableSession -> SessionStartup.STOP
    authState is AuthState.RequiresTwoFactor -> SessionStartup.STOP
    else -> SessionStartup.RETRY
}

private const val SESSION_RETRY_BASE_DELAY_MS = 10_000L
private const val SESSION_RETRY_MAX_DELAY_MS = 300_000L

/**
 * Backoff between session-resume attempts. Each attempt re-runs AuthRepository's
 * own 0/2s/5s ladder, so the first wait starts well clear of it.
 */
internal fun sessionRetryDelayMs(attempt: Int): Long {
    val exponent = (attempt.coerceAtLeast(1) - 1).coerceAtMost(10)
    return minOf(SESSION_RETRY_BASE_DELAY_MS shl exponent, SESSION_RETRY_MAX_DELAY_MS)
}

private const val MAX_REMOTE_NOTIFICATION_CHARS = 120

/**
 * Cap text that came straight off the pipeline. Notification types this app does
 * not model are rendered verbatim, so the originator chooses what appears on the
 * lock screen; a bound keeps that to a line.
 */
internal fun boundRemoteText(value: String, max: Int = MAX_REMOTE_NOTIFICATION_CHARS): String =
    if (value.length <= max) value else value.take(max - 1).trimEnd() + "…"

/** Null rather than a throw when VRChat sends the other shape for a field. */
private fun PipelineEvent.contentObject(): JsonObject? = content as? JsonObject

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
