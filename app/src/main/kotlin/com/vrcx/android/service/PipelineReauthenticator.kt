package com.vrcx.android.service

import com.vrcx.android.data.repository.AccountScope
import com.vrcx.android.data.repository.AuthRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Refreshes a handshake-rejected token without allowing parallel refresh loops. */
internal class PipelineReauthenticator(
    private val authRepository: AuthRepository,
    private val state: PipelineRuntimeState,
    private val stop: (CoroutineScope) -> Unit,
) {
    fun request(origin: AccountScope.Token, scope: CoroutineScope) {
        if (state.isCurrent(scope, origin)) {
            val job = state.withPipeline(scope) {
                if (reauthJob?.isActive == true) {
                    null
                } else {
                    scope.launch(start = CoroutineStart.LAZY) { reauthenticate(origin, scope) }
                        .also { reauthJob = it }
                }
            }
            job?.start()
        } else {
            stop(scope)
        }
    }

    private suspend fun reauthenticate(origin: AccountScope.Token, scope: CoroutineScope) {
        if (state.isCurrent(scope, origin)) {
            val rejectionCount = state.withPipeline(scope) { ++handshakeRejections }
            if (rejectionCount != null) {
                // Back off first, so a persistently refused token cannot turn into
                // a request storm against the auth endpoints.
                delay(sessionRetryDelayMs(rejectionCount))
                refreshAndReconnect(origin, scope)
            }
        } else {
            stop(scope)
        }
    }

    private suspend fun refreshAndReconnect(origin: AccountScope.Token, scope: CoroutineScope) {
        if (state.isCurrent(scope, origin)) {
            val session = authRepository.refreshPipelineSession(origin)
            if (session == null) {
                stop(scope)
            } else {
                val currentSocket = state.withPipeline(scope) {
                    authToken = session.authToken
                    socket
                }
                if (currentSocket != null) {
                    state.socketAction(
                        scope = scope,
                        origin = origin,
                        socket = currentSocket,
                        action = PipelineSocketAction.RECONNECT,
                    )
                }
            }
        } else {
            stop(scope)
        }
    }
}
