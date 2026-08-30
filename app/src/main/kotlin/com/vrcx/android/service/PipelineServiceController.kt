package com.vrcx.android.service

import android.util.Log
import com.vrcx.android.data.preferences.NotificationPolicy
import com.vrcx.android.data.repository.AccountScope
import com.vrcx.android.data.repository.PipelineSession
import com.vrcx.android.data.util.runCatchingCancellable
import com.vrcx.android.data.websocket.VRChatWebSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Owns one account-scoped pipeline from session readiness through teardown. */
internal class PipelineServiceController(
    private val repositories: PipelineRepositories,
    private val transport: PipelineTransport,
    environment: PipelineServiceEnvironment,
    private val parentScope: CoroutineScope,
    private val onStop: () -> Unit,
) {
    private val state = PipelineRuntimeState(repositories.accountScope, parentScope)
    private val networkMonitor = PipelineNetworkMonitor(environment.connectivityManager, state, ::stop)
    private val dispose: (DetachedPipeline) -> Unit = { pipeline ->
        pipeline.scope?.cancel()
        pipeline.socket?.disconnect()
        networkMonitor.unregister(pipeline.networkCallback)
    }
    private val eventRouter = PipelineEventRouter(
        repositories = repositories,
        state = state,
        stateResynchronizer = environment.stateResynchronizer,
        notificationHelper = environment.notificationHelper,
    )
    private val reauthenticator = PipelineReauthenticator(repositories.auth, state, ::stop)
    private val preferences = environment.preferences
    private val notificationHelper = environment.notificationHelper

    fun start() {
        when (val result = state.begin(::runStartup)) {
            PipelineStart.AlreadyRunning -> Unit
            is PipelineStart.Started -> result.detached?.let(dispose)
        }
    }

    private suspend fun runStartup(scope: CoroutineScope) {
        try {
            val failure = runCatchingCancellable { startPipeline(scope) }.exceptionOrNull()
            if (failure != null) {
                Log.e(SERVICE_LOG_TAG, "WebSocket service startup failed", failure)
                stop(scope)
            }
        } finally {
            state.finishStartup(scope)
        }
    }

    private suspend fun startPipeline(scope: CoroutineScope) {
        if (!awaitPipelineSession(repositories.auth)) {
            stop(scope)
        } else {
            val session = repositories.auth.pipelineSession()
            when {
                shouldRestartPipelineStartup(repositories.accountScope, session) -> restart(scope)
                session == null -> restart(scope)
                !state.claim(scope, session.account, session.authToken) -> restart(scope)
                else -> launchPipeline(scope, session)
            }
        }
    }

    private suspend fun launchPipeline(scope: CoroutineScope, session: PipelineSession) {
        val origin = session.account
        scope.launch {
            watchPipelineSession(
                authState = repositories.auth.authState,
                expectedUserId = origin.ownerUserId,
                stop = { stop(scope) },
                restart = ::start,
            )
        }
        if (prepareConsumers(scope, origin)) openSocket(scope, origin)
    }

    private suspend fun prepareConsumers(scope: CoroutineScope, origin: AccountScope.Token): Boolean {
        runCatchingCancellable { repositories.friends.loadFriendsList(origin) }
            .onFailure { Log.w(SERVICE_LOG_TAG, "Failed to preload friends list", it) }
        var prepared = false
        if (state.isCurrent(scope, origin)) {
            val settings = combine(
                preferences.notificationPolicy,
                repositories.friends.observeNotifyEnabledIds(origin.ownerUserId),
            ) { policy: NotificationPolicy, enabledFriendIds: Set<String> ->
                PipelineNotificationSettings(policy, enabledFriendIds)
            }
            launchInitialValueCollector(scope, settings) { snapshot ->
                state.withPipeline(scope) { notificationSettings = snapshot }
            }
            if (state.isCurrent(scope, origin)) {
                launchSubscribedCollector(scope, repositories.friends.friendTransitions) { event ->
                    val enabled = state.withPipeline(scope) {
                        event.value.userId in notificationSettings.enabledFriendIds
                    } == true
                    if (enabled) {
                        consumeAccountScopedPipelineEvent(repositories.accountScope, origin, event) { transition ->
                            notifyFriendTransition(notificationHelper, transition)
                        }
                    }
                }
                prepared = true
            }
        }
        if (!prepared) {
            stop(scope)
        }
        return prepared
    }

    private suspend fun openSocket(scope: CoroutineScope, origin: AccountScope.Token) {
        val socket = VRChatWebSocket(transport.json, transport.client, transport.dispatcher) {
            reauthenticator.request(origin, scope)
        }
        if (state.install(scope, socket)) {
            launchSubscribedCollector(scope, socket.events) { event -> eventRouter.route(event, origin, scope) }
            if (state.socketAction(scope, origin, socket, PipelineSocketAction.CONNECT)) {
                networkMonitor.register(origin, scope)
            } else {
                stop(scope)
            }
        } else {
            socket.disconnect()
        }
    }

    private fun stop(scope: CoroutineScope) {
        if (clear(scope)) onStop()
    }

    private fun restart(scope: CoroutineScope) {
        if (clear(scope)) start()
    }

    fun clear(expectedScope: CoroutineScope? = null): Boolean {
        val detached = state.detach(expectedScope)
        detached?.let(dispose)
        return detached != null
    }

    fun close() {
        clear()
        parentScope.cancel()
    }
}
