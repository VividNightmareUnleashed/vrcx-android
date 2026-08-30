package com.vrcx.android.service

import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.vrcx.android.data.repository.AccountScope
import com.vrcx.android.data.websocket.VRChatWebSocket
import com.vrcx.android.data.websocket.shouldForceReconnect
import kotlinx.coroutines.CoroutineScope

private sealed interface NetworkAction {
    data object Ignore : NetworkAction

    data object Stop : NetworkAction

    data class Reconnect(val socket: VRChatWebSocket) : NetworkAction
}

/** Owns callback registration and network handoff decisions for one pipeline. */
internal class PipelineNetworkMonitor(
    private val connectivityManager: ConnectivityManager,
    private val state: PipelineRuntimeState,
    private val stop: (CoroutineScope) -> Unit,
) {
    fun register(origin: AccountScope.Token, scope: CoroutineScope) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(SERVICE_LOG_TAG, "Network available")
                perform(availableAction(origin, scope, network), origin, scope)
            }

            override fun onLost(network: Network) {
                Log.d(SERVICE_LOG_TAG, "Network lost")
                perform(lostAction(origin, scope, network), origin, scope)
            }
        }
        state.withPipeline(scope) {
            if (networkCallback == null) {
                connectivityManager.registerDefaultNetworkCallback(callback)
                networkCallback = callback
            }
        }
    }

    fun unregister(callback: ConnectivityManager.NetworkCallback?) {
        callback ?: return
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
            .onFailure { Log.w(SERVICE_LOG_TAG, "Unable to unregister network callback", it) }
    }

    private fun availableAction(origin: AccountScope.Token, scope: CoroutineScope, network: Network): NetworkAction =
        when {
            !state.isCurrent(scope, origin) -> NetworkAction.Stop

            else -> state.withPipeline(scope) {
                val previousNetwork = activeNetwork
                activeNetwork = network
                val currentSocket = socket
                val networkWasReplaced = previousNetwork != null && previousNetwork != network
                if (currentSocket != null && shouldForceReconnect(networkWasReplaced, currentSocket.state.value)) {
                    NetworkAction.Reconnect(currentSocket)
                } else {
                    NetworkAction.Ignore
                }
            } ?: NetworkAction.Ignore
        }

    private fun lostAction(origin: AccountScope.Token, scope: CoroutineScope, network: Network): NetworkAction = when {
        !state.isCurrent(scope, origin) -> NetworkAction.Stop

        else -> state.withPipeline(scope) {
            if (activeNetwork == network) {
                activeNetwork = null
                socket?.let { NetworkAction.Reconnect(it) } ?: NetworkAction.Ignore
            } else {
                NetworkAction.Ignore
            }
        } ?: NetworkAction.Ignore
    }

    private fun perform(action: NetworkAction, origin: AccountScope.Token, scope: CoroutineScope) {
        when (action) {
            NetworkAction.Ignore -> Unit

            NetworkAction.Stop -> stop(scope)

            is NetworkAction.Reconnect -> state.socketAction(
                scope = scope,
                origin = origin,
                socket = action.socket,
                action = PipelineSocketAction.RECONNECT,
            )
        }
    }
}
