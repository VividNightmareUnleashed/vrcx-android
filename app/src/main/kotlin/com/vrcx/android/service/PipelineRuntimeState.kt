package com.vrcx.android.service

import android.net.ConnectivityManager
import android.net.Network
import com.vrcx.android.data.preferences.NotificationPolicy
import com.vrcx.android.data.repository.AccountScope
import com.vrcx.android.data.websocket.VRChatWebSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal data class PipelineNotificationSettings(val policy: NotificationPolicy, val enabledFriendIds: Set<String>)

internal class ActivePipeline(val scope: CoroutineScope) {
    var account: AccountScope.Token? = null
    var authToken: String? = null
    var socket: VRChatWebSocket? = null
    var startupJob: Job? = null
    var reauthJob: Job? = null
    var handshakeRejections = 0
    var networkCallback: ConnectivityManager.NetworkCallback? = null
    var activeNetwork: Network? = null
    var notificationSettings = PipelineNotificationSettings(NotificationPolicy.DISABLED, emptySet())
}

internal data class DetachedPipeline(
    val scope: CoroutineScope?,
    val socket: VRChatWebSocket?,
    val networkCallback: ConnectivityManager.NetworkCallback?,
)

internal sealed interface PipelineStart {
    data object AlreadyRunning : PipelineStart

    data class Started(val detached: DetachedPipeline?) : PipelineStart
}

internal enum class PipelineSocketAction {
    CONNECT,
    RECONNECT,
    RECOVER,
    ;

    fun execute(socket: VRChatWebSocket, authToken: String) {
        when (this) {
            CONNECT -> socket.connect(authToken)
            RECONNECT -> socket.reconnectNow(authToken)
            RECOVER -> socket.reconnectAfterRecovery(authToken)
        }
    }
}

/** Owns the mutable state of one account-scoped pipeline behind a single lock. */
internal class PipelineRuntimeState(private val accountScope: AccountScope, private val parentScope: CoroutineScope) {
    private val lock = Any()
    private var active: ActivePipeline? = null

    fun begin(startup: suspend (CoroutineScope) -> Unit): PipelineStart = synchronized(lock) {
        val current = active
        val isRunning = current?.let { it.socket != null || it.startupJob?.isActive == true } == true
        val currentAccount = current?.account
        if (isRunning && (currentAccount == null || accountScope.isCurrent(currentAccount))) {
            PipelineStart.AlreadyRunning
        } else {
            val detached = current?.let { detachLocked() }
            val scope = CoroutineScope(
                parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]),
            )
            val pipeline = ActivePipeline(scope)
            active = pipeline
            pipeline.startupJob = scope.launch { startup(scope) }
            PipelineStart.Started(detached)
        }
    }

    fun finishStartup(scope: CoroutineScope) {
        withPipeline(scope) { startupJob = null }
    }

    fun claim(scope: CoroutineScope, account: AccountScope.Token, authToken: String): Boolean = synchronized(lock) {
        val pipeline = active?.takeIf { it.scope === scope }
        when {
            pipeline == null -> false

            !accountScope.isCurrent(account) -> false

            else -> {
                pipeline.account = account
                pipeline.authToken = authToken
                true
            }
        }
    }

    fun install(scope: CoroutineScope, socket: VRChatWebSocket): Boolean = withPipeline(scope) {
        this.socket = socket
        true
    } ?: false

    fun isCurrent(scope: CoroutineScope, origin: AccountScope.Token): Boolean = synchronized(lock) {
        active?.takeIf { it.scope === scope }?.let { pipeline ->
            pipeline.account == origin && accountScope.isCurrent(origin)
        } == true
    }

    fun <T> withPipeline(scope: CoroutineScope, block: ActivePipeline.() -> T): T? = synchronized(lock) {
        active?.takeIf { it.scope === scope }?.block()
    }

    fun socketAction(
        scope: CoroutineScope,
        origin: AccountScope.Token,
        socket: VRChatWebSocket,
        action: PipelineSocketAction,
    ): Boolean = synchronized(lock) {
        val pipeline = active?.takeIf { it.scope === scope && it.socket === socket }
        val authToken = pipeline?.authToken
        when {
            pipeline?.account != origin -> false
            authToken == null -> false
            else -> accountScope.publishIfCurrent(origin) { action.execute(socket, authToken) }
        }
    }

    fun detach(expectedScope: CoroutineScope? = null): DetachedPipeline? = synchronized(lock) {
        if (expectedScope == null || active?.scope === expectedScope) detachLocked() else null
    }

    private fun detachLocked(): DetachedPipeline {
        val pipeline = active
        active = null
        return DetachedPipeline(
            scope = pipeline?.scope,
            socket = pipeline?.socket,
            networkCallback = pipeline?.networkCallback,
        )
    }
}
