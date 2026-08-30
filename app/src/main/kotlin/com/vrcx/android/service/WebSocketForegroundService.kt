package com.vrcx.android.service

import android.annotation.SuppressLint
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
import com.vrcx.android.data.preferences.NotificationPolicy
import com.vrcx.android.data.preferences.VrcxPreferences
import com.vrcx.android.data.repository.AccountScope
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.FriendRepository
import com.vrcx.android.data.repository.GalleryRepository
import com.vrcx.android.data.repository.GroupRepository
import com.vrcx.android.data.repository.NotificationRepository
import com.vrcx.android.data.repository.UnifiedNotification
import com.vrcx.android.data.websocket.PipelineEvent
import com.vrcx.android.data.websocket.PipelineOkHttpClient
import com.vrcx.android.data.websocket.VRChatWebSocket
import com.vrcx.android.data.websocket.shouldForceReconnect
import com.vrcx.android.di.IoDispatcher
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal const val SERVICE_LOG_TAG = "WebSocketForegroundService"

private data class NotificationSettings(val policy: NotificationPolicy, val enabledFriendIds: Set<String>)

private data class DetachedPipeline(
    val scope: CoroutineScope?,
    val socket: VRChatWebSocket?,
    val networkCallback: ConnectivityManager.NetworkCallback?,
)

private data class PipelineConnection(val socket: VRChatWebSocket, val authToken: String, val previousNetwork: Network?)

internal suspend fun <T> launchSubscribedCollector(
    scope: CoroutineScope,
    events: SharedFlow<T>,
    consume: suspend (T) -> Unit,
): Job {
    val subscribed = CompletableDeferred<Unit>()
    val collector = scope.launch {
        events
            .onSubscription { subscribed.complete(Unit) }
            .collect { event -> consume(event) }
    }
    collector.invokeOnCompletion { cause ->
        subscribed.completeExceptionally(
            cause ?: IllegalStateException("Event collector completed before subscribing"),
        )
    }
    try {
        subscribed.await()
    } catch (cancellation: CancellationException) {
        collector.cancel(cancellation)
        throw cancellation
    }
    return collector
}

@AndroidEntryPoint
class WebSocketForegroundService : Service() {

    @Inject lateinit var authRepository: AuthRepository

    @Inject lateinit var accountScope: AccountScope

    @Inject lateinit var friendRepository: FriendRepository

    @Inject lateinit var notificationRepository: NotificationRepository

    @Inject lateinit var groupRepository: GroupRepository

    @Inject lateinit var galleryRepository: GalleryRepository

    @Inject lateinit var json: Json

    @Inject lateinit var okHttpClient: PipelineOkHttpClient

    @Inject lateinit var preferences: VrcxPreferences

    @Inject lateinit var notificationHelper: NotificationHelper

    @Inject lateinit var pipelineStateResynchronizer: PipelineStateResynchronizer

    @Inject @IoDispatcher
    lateinit var ioDispatcher: CoroutineDispatcher

    @Volatile private var prefNotifyInvite = false

    @Volatile private var prefNotifyFriendRequest = false

    @Volatile private var prefNotifyGeneral = false

    @Volatile private var notifyEnabledFriendIds: Set<String> = emptySet()

    // Written on serviceScope, read from the ConnectivityManager callback thread
    // and from onDestroy on the main thread. Observing a stale null there drops
    // the reconnect after a network transition, or skips the socket teardown.
    @Volatile private var webSocket: VRChatWebSocket? = null

    @Volatile private var currentAuthToken: String? = null
    private val serviceScope by lazy(LazyThreadSafetyMode.NONE) {
        CoroutineScope(SupervisorJob() + ioDispatcher)
    }
    private val pipelineLock = Any()
    private var pipelineScope: CoroutineScope? = null
    private var activePipelineAccount: AccountScope.Token? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile private var activeNetwork: Network? = null
    private var startupJob: Job? = null

    @Volatile private var reauthJob: Job? = null

    @Volatile private var handshakeRejections = 0

    override fun onBind(intent: Intent?): IBinder? = null

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
        notificationHelper.cancelServiceReconnectRequired()
        if (mode == ServiceMode.FOREGROUND) {
            // Must call startForeground immediately to avoid crash on Android 12+
            startForeground(
                NotificationHelper.SERVICE_NOTIFICATION_ID,
                notificationHelper.createWebSocketServiceNotification(),
            )
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
            notificationHelper.notifyServiceReconnectRequired()
            clearPipeline()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
    }

    private fun startWebSocket() {
        var detached: DetachedPipeline? = null
        synchronized(pipelineLock) {
            val hasPipeline = webSocket != null || startupJob?.isActive == true
            if (hasPipeline) {
                val origin = activePipelineAccount
                if (origin == null || isCurrentPipelineOrigin(accountScope, origin)) return
                detached = detachPipelineLocked()
            }
            val scope = CoroutineScope(
                serviceScope.coroutineContext + SupervisorJob(serviceScope.coroutineContext[Job]),
            )
            pipelineScope = scope
            startupJob = scope.launch { runStartup(scope) }
        }
        detached?.let(::disposePipeline)
    }

    private suspend fun runStartup(scope: CoroutineScope) {
        try {
            if (!awaitSession(scope)) return
            val pipelineSession = authRepository.pipelineSession()
            if (shouldRestartPipelineStartup(accountScope, pipelineSession)) {
                restartPipeline(scope)
                return
            }
            val readySession = requireNotNull(pipelineSession)
            val token = readySession.authToken
            val pipelineAccount = readySession.account
            if (!claimPipeline(scope, pipelineAccount, token)) {
                restartPipeline(scope)
                return
            }
            val userId = pipelineAccount.ownerUserId

            // The service owns its own lifecycle, so the session ending — an
            // explicit sign-out or a 401 AuthRepository confirmed — is what takes
            // the socket and the ongoing notification down. The data layer does
            // not reach into the service to do it.
            scope.launch {
                watchPipelineSession(
                    authState = authRepository.authState,
                    expectedUserId = userId,
                    stop = { stopWithCleanup(scope) },
                    restart = ::startWebSocket,
                )
            }

            try {
                friendRepository.loadFriendsList(pipelineAccount)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.w(SERVICE_LOG_TAG, "Failed to preload friends list", error)
            }
            if (!isCurrentPipelineOrigin(accountScope, pipelineAccount)) {
                stopWithCleanup(scope)
                return
            }

            // Policy must be available before the handshake burst begins.
            val notificationPreferencesReady = CompletableDeferred<Unit>()
            scope.launch {
                try {
                    combine(
                        preferences.notificationPolicy,
                        friendRepository.observeNotifyEnabledIds(userId),
                    ) { policy: NotificationPolicy, enabledFriendIds: Set<String> ->
                        NotificationSettings(policy, enabledFriendIds)
                    }.collect { snapshot ->
                        publishNotificationSettings(scope, snapshot)
                        notificationPreferencesReady.complete(Unit)
                    }
                    if (!notificationPreferencesReady.isCompleted) {
                        notificationPreferencesReady.completeExceptionally(
                            IllegalStateException("Notification policy completed before its initial value"),
                        )
                    }
                } catch (cancellation: CancellationException) {
                    notificationPreferencesReady.cancel(cancellation)
                    throw cancellation
                } catch (error: Exception) {
                    notificationPreferencesReady.completeExceptionally(error)
                }
            }
            notificationPreferencesReady.await()
            if (!isCurrentPipelineOrigin(accountScope, pipelineAccount)) {
                stopWithCleanup(scope)
                return
            }

            // This replay-zero flow is fed by handshake frames, so it must be
            // subscribed before the socket can deliver its initial burst.
            launchSubscribedCollector(scope, friendRepository.friendTransitions) { event ->
                if (isFriendNotificationEnabled(scope, event.value.userId)) {
                    consumeAccountScopedPipelineEvent(accountScope, pipelineAccount, event) { transition ->
                        notifyFriendTransition(notificationHelper, transition)
                    }
                }
            }

            val ws = VRChatWebSocket(json, okHttpClient, ioDispatcher) {
                refreshTokenAndReconnect(pipelineAccount, scope)
            }
            if (!installWebSocket(scope, ws)) return
            // Subscribe before connecting. The event flow has no replay, so
            // anything VRChat pushes between the handshake completing and
            // the collector arriving would be dropped without a trace —
            // including the burst it sends immediately after connect.
            launchSubscribedCollector(scope, ws.events) { event ->
                routeEvent(event, pipelineAccount, scope)
            }
            if (!connectPipeline(scope, pipelineAccount, ws, token)) {
                stopWithCleanup(scope)
                return
            }

            registerNetworkCallback(pipelineAccount, scope)
        } catch (cancellation: CancellationException) {
            // onDestroy cancels serviceScope; that is a normal shutdown and must
            // not be answered with more teardown.
            throw cancellation
        } catch (error: Exception) {
            // startForeground has already run by this point, so leaving the job
            // dead would strand an "ongoing" notification over no connection.
            Log.e(SERVICE_LOG_TAG, "WebSocket service startup failed", error)
            stopWithCleanup(scope)
        } finally {
            synchronized(pipelineLock) {
                if (pipelineScope === scope) startupJob = null
            }
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
    private suspend fun awaitSession(scope: CoroutineScope): Boolean {
        var attempt = 0
        while (true) {
            if (authRepository.ensureSessionReady()) return true
            val decision = sessionStartupDecision(
                authState = authRepository.authState.value,
                hasResumableSession = authRepository.hasResumableSession(),
            )
            if (decision == SessionStartup.STOP) {
                stopWithCleanup(scope)
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
    private fun refreshTokenAndReconnect(origin: AccountScope.Token, scope: CoroutineScope) {
        if (!isCurrentPipelineOrigin(accountScope, origin)) {
            stopWithCleanup(scope)
            return
        }
        synchronized(pipelineLock) {
            if (pipelineScope !== scope || reauthJob?.isActive == true) return
            reauthJob = scope.launch {
                if (!isCurrentPipelineOrigin(accountScope, origin)) {
                    stopWithCleanup(scope)
                    return@launch
                }
                val rejectionCount = synchronized(pipelineLock) {
                    if (pipelineScope !== scope) return@synchronized null
                    ++handshakeRejections
                } ?: return@launch
                // Back off first, so a persistently refused token cannot turn into
                // a request storm against the auth endpoints.
                delay(sessionRetryDelayMs(rejectionCount))
                if (!isCurrentPipelineOrigin(accountScope, origin)) {
                    stopWithCleanup(scope)
                    return@launch
                }
                val session = authRepository.refreshPipelineSession(origin) ?: run {
                    stopWithCleanup(scope)
                    return@launch
                }
                // A fetch that failed leaves the old token in the same session, and
                // retrying it keeps the rejection loop alive rather than parking.
                val socket = synchronized(pipelineLock) {
                    if (pipelineScope !== scope) return@synchronized null
                    currentAuthToken = session.authToken
                    webSocket
                } ?: return@launch
                reconnectPipeline(scope, origin, socket, session.authToken)
            }
        }
    }

    private fun stopWithCleanup(expectedScope: CoroutineScope? = null) {
        if (!clearPipeline(expectedScope)) return
        if (serviceMode(this) == ServiceMode.FOREGROUND) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    private fun restartPipeline(expectedScope: CoroutineScope) {
        if (clearPipeline(expectedScope)) startWebSocket()
    }

    private suspend fun routeEvent(event: PipelineEvent, origin: AccountScope.Token, scope: CoroutineScope) {
        if (!isCurrentPipelineOrigin(accountScope, origin)) return
        if (event === PipelineEvent.StreamGap) {
            recoverPipelineState(origin, scope)
            return
        }
        containPipelineFailure("friends") { friendRepository.handleEvent(event, origin) }
        if (!isCurrentPipelineOrigin(accountScope, origin)) return
        val notification = containPipelineFailure("notifications") {
            notificationRepository.handleEvent(event, origin)
        }
        if (!isCurrentPipelineOrigin(accountScope, origin)) return
        containPipelineFailure("session") { authRepository.handleEvent(event, origin) }
        if (!isCurrentPipelineOrigin(accountScope, origin)) return
        containPipelineFailure("groups") { groupRepository.handleEvent(event, origin) }
        if (!isCurrentPipelineOrigin(accountScope, origin)) return
        containPipelineFailure("gallery") { handleContentRefresh(event, origin, scope) }
        notification?.let { scopedNotification ->
            consumeAccountScopedPipelineEvent(accountScope, origin, scopedNotification) { value ->
                containPipelineFailure("system notifications") { dispatchNotification(value) }
            }
        }
    }

    private suspend fun recoverPipelineState(origin: AccountScope.Token, scope: CoroutineScope) {
        val recovered = retryPipelineStateRecovery(
            isCurrent = { isPipelineRecoveryCurrent(origin, scope) },
            recoverCore = { pipelineStateResynchronizer.resynchronizeCore(origin) },
        )
        if (!recovered) return
        val connection = currentPipelineConnection(scope) ?: return
        if (resumePipelineAfterRecovery(scope, origin, connection.socket, connection.authToken)) {
            scope.launch {
                if (isPipelineRecoveryCurrent(origin, scope)) {
                    pipelineStateResynchronizer.resynchronizeGallery(origin)
                }
            }
        }
    }

    private fun isPipelineRecoveryCurrent(origin: AccountScope.Token, scope: CoroutineScope): Boolean =
        isCurrentPipelineOrigin(accountScope, origin) && synchronized(pipelineLock) { pipelineScope === scope }

    private fun currentPipelineConnection(scope: CoroutineScope): PipelineConnection? = synchronized(pipelineLock) {
        val socket = webSocket
        val authToken = currentAuthToken
        if (pipelineScope === scope && socket != null && authToken != null) {
            PipelineConnection(socket, authToken, activeNetwork)
        } else {
            null
        }
    }

    private fun registerNetworkCallback(origin: AccountScope.Token, scope: CoroutineScope) {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(SERVICE_LOG_TAG, "Network available")
                if (!isCurrentPipelineOrigin(accountScope, origin)) {
                    stopWithCleanup(scope)
                    return
                }
                val connection = synchronized(pipelineLock) {
                    if (pipelineScope !== scope) return
                    val previousNetwork = activeNetwork
                    activeNetwork = network
                    PipelineConnection(webSocket ?: return, currentAuthToken ?: return, previousNetwork)
                }
                val networkWasReplaced =
                    connection.previousNetwork != null && connection.previousNetwork != network
                if (shouldForceReconnect(networkWasReplaced, connection.socket.state.value)) {
                    reconnectPipeline(scope, origin, connection.socket, connection.authToken)
                }
            }

            override fun onLost(network: Network) {
                Log.d(SERVICE_LOG_TAG, "Network lost")
                if (!isCurrentPipelineOrigin(accountScope, origin)) {
                    stopWithCleanup(scope)
                    return
                }
                val connection = synchronized(pipelineLock) {
                    if (pipelineScope !== scope || activeNetwork != network) return
                    activeNetwork = null
                    PipelineConnection(webSocket ?: return, currentAuthToken ?: return, null)
                }
                reconnectPipeline(scope, origin, connection.socket, connection.authToken)
            }
        }
        synchronized(pipelineLock) {
            if (pipelineScope !== scope || networkCallback != null) return
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        }
    }

    private fun claimPipeline(scope: CoroutineScope, account: AccountScope.Token, authToken: String): Boolean =
        synchronized(pipelineLock) {
            if (pipelineScope !== scope || !isCurrentPipelineOrigin(accountScope, account)) return false
            activePipelineAccount = account
            currentAuthToken = authToken
            true
        }

    private fun installWebSocket(scope: CoroutineScope, socket: VRChatWebSocket): Boolean = synchronized(pipelineLock) {
        if (pipelineScope !== scope) return false
        webSocket = socket
        true
    }

    private fun publishNotificationSettings(scope: CoroutineScope, settings: NotificationSettings): Boolean =
        synchronized(pipelineLock) {
            if (pipelineScope !== scope) return false
            prefNotifyInvite = settings.policy.invites
            prefNotifyFriendRequest = settings.policy.friendRequests
            prefNotifyGeneral = settings.policy.general
            notifyEnabledFriendIds = settings.enabledFriendIds
            true
        }

    private fun isFriendNotificationEnabled(scope: CoroutineScope, userId: String): Boolean =
        synchronized(pipelineLock) {
            pipelineScope === scope && userId in notifyEnabledFriendIds
        }

    private fun connectPipeline(
        scope: CoroutineScope,
        origin: AccountScope.Token,
        socket: VRChatWebSocket,
        authToken: String,
    ): Boolean = synchronized(pipelineLock) {
        if (pipelineScope !== scope || webSocket !== socket) return false
        accountScope.publishIfCurrent(origin) { socket.connect(authToken) }
    }

    private fun reconnectPipeline(
        scope: CoroutineScope,
        origin: AccountScope.Token,
        socket: VRChatWebSocket,
        authToken: String,
    ): Boolean = synchronized(pipelineLock) {
        if (pipelineScope !== scope || webSocket !== socket) return false
        accountScope.publishIfCurrent(origin) { socket.reconnectNow(authToken) }
    }

    private fun resumePipelineAfterRecovery(
        scope: CoroutineScope,
        origin: AccountScope.Token,
        socket: VRChatWebSocket,
        authToken: String,
    ): Boolean = synchronized(pipelineLock) {
        if (pipelineScope !== scope || webSocket !== socket) return false
        accountScope.publishIfCurrent(origin) { socket.reconnectAfterRecovery(authToken) }
    }

    private fun clearPipeline(expectedScope: CoroutineScope? = null): Boolean {
        val detached = synchronized(pipelineLock) {
            if (expectedScope != null && pipelineScope !== expectedScope) return false
            detachPipelineLocked()
        }
        disposePipeline(detached)
        return true
    }

    private fun detachPipelineLocked(): DetachedPipeline {
        val detached = DetachedPipeline(
            scope = pipelineScope,
            socket = webSocket,
            networkCallback = networkCallback,
        )
        pipelineScope = null
        startupJob = null
        reauthJob = null
        activePipelineAccount = null
        currentAuthToken = null
        handshakeRejections = 0
        prefNotifyInvite = false
        prefNotifyFriendRequest = false
        prefNotifyGeneral = false
        notifyEnabledFriendIds = emptySet()
        networkCallback = null
        activeNetwork = null
        webSocket = null
        return detached
    }

    private fun disposePipeline(pipeline: DetachedPipeline) {
        pipeline.scope?.cancel()
        pipeline.socket?.disconnect()
        pipeline.networkCallback?.let { callback ->
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            runCatching { cm.unregisterNetworkCallback(callback) }
                .onFailure { Log.w(SERVICE_LOG_TAG, "Unable to unregister network callback", it) }
        }
    }

    override fun onDestroy() {
        clearPipeline()
        serviceScope.cancel()
        // onTimeout stops the service, so onDestroy runs straight after it —
        // writing NONE here unconditionally would erase the recovery it just
        // recorded.
        if (serviceMode(this) != ServiceMode.TIMED_OUT) {
            setServiceMode(this, ServiceMode.NONE)
        }
        super.onDestroy()
    }

    private fun handleContentRefresh(event: PipelineEvent, origin: AccountScope.Token, scope: CoroutineScope) {
        if (event !is PipelineEvent.ContentRefresh) return
        val contentType = event.contentObject()?.stringOrNull("contentType") ?: return
        val userId = origin.ownerUserId
        // The boundary belongs inside the launch: a throw from the child would
        // otherwise reach the default handler long after routeEvent returned.
        scope.launch {
            containPipelineFailure("gallery refresh") {
                galleryRepository.handleContentRefresh(contentType, userId, origin)
            }
        }
    }

    private fun dispatchNotification(notification: UnifiedNotification) {
        dispatchNotification(
            notificationHelper,
            notification,
            prefNotifyInvite,
            prefNotifyFriendRequest,
            prefNotifyGeneral,
        )
    }

    companion object {
        private const val ACTION_START = "com.vrcx.android.START_WEBSOCKET"
        private const val ACTION_START_NON_FOREGROUND = "com.vrcx.android.START_WEBSOCKET_NON_FOREGROUND"
        private const val SERVICE_STATE_PREFERENCES = "websocket_service_state"
        private const val KEY_SERVICE_MODE = "requested_mode"

        private fun statePreferences(context: Context): SharedPreferences =
            context.getSharedPreferences(SERVICE_STATE_PREFERENCES, Context.MODE_PRIVATE)

        internal fun serviceMode(context: Context): ServiceMode =
            ServiceMode.fromStored(statePreferences(context).getInt(KEY_SERVICE_MODE, ServiceMode.NONE.storedValue))

        internal fun setServiceMode(context: Context, mode: ServiceMode, synchronous: Boolean = false) {
            val editor = statePreferences(context).edit().putInt(KEY_SERVICE_MODE, mode.storedValue)
            if (synchronous) persistBeforePossibleProcessExit(editor) else editor.apply()
        }

        /** The timeout callback may be followed immediately by process teardown. */
        @SuppressLint("ApplySharedPref")
        private fun persistBeforePossibleProcessExit(editor: SharedPreferences.Editor) {
            editor.commit()
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
            // Explicit stop revokes timeout recovery even after the service self-terminated.
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

/** Null rather than a throw when VRChat sends the other shape for a field. */
private fun PipelineEvent.contentObject(): JsonObject? = content as? JsonObject

private fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf {
    it !is JsonNull
}?.content
