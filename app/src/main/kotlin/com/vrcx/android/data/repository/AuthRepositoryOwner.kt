package com.vrcx.android.data.repository

import com.vrcx.android.data.api.AuthApi
import com.vrcx.android.data.api.AuthEvent
import com.vrcx.android.data.api.AuthEventBus
import com.vrcx.android.data.api.CookieJarImpl
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** Wires the focused auth collaborators around one session state and one cookie owner. */
internal class AuthRepositoryOwner(
    authApi: AuthApi,
    cookieJar: CookieJarImpl,
    logoutStorage: AuthLogoutStorage,
    json: Json,
    val sessionRuntime: AuthSessionRuntime,
    ioDispatcher: CoroutineDispatcher,
) {
    val state = AuthSessionState(sessionRuntime)
    val cookies = AuthCookieStore(cookieJar, ioDispatcher)
    private val remote = AuthRemoteGateway(authApi, json)
    private val publisher = AuthSessionPublisher(state, remote, cookies)
    val loginActions: AuthLoginActions = DefaultAuthLoginActions(
        state = state,
        remote = remote,
        cookies = cookies,
        publisher = publisher,
        sessionRuntime = sessionRuntime,
    )
    val sessionActions: AuthSessionActions = DefaultAuthSessionActions(
        state = state,
        remote = remote,
        cookies = cookies,
        publisher = publisher,
        logoutStorage = logoutStorage,
        sessionRuntime = sessionRuntime,
    )
    val pipeline = AuthPipelineCoordinator(
        state = state,
        remote = remote,
        cookies = cookies,
        publisher = publisher,
        sessionRuntime = sessionRuntime,
        eventReducer = AuthUserEventReducer(json),
    )

    fun start(defaultDispatcher: CoroutineDispatcher, authEventBus: AuthEventBus?) {
        val scope = CoroutineScope(SupervisorJob() + defaultDispatcher)
        scope.launch { cookies.monitorStorageStatus() }
        authEventBus?.let { bus ->
            scope.launch {
                bus.events.collect { event ->
                    when (event) {
                        AuthEvent.Unauthorized -> pipeline.handleUnauthorizedSignal()
                    }
                }
            }
        }
    }
}
