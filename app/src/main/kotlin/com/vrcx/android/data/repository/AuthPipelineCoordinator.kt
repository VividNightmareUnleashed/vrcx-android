package com.vrcx.android.data.repository

import com.vrcx.android.data.api.model.CurrentUser
import com.vrcx.android.data.websocket.PipelineEvent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** Owns re-authentication and account-scoped pipeline mutations. */
internal class AuthPipelineCoordinator(
    private val state: AuthSessionState,
    private val remote: AuthRemoteGateway,
    private val cookies: AuthCookieStore,
    private val publisher: AuthSessionPublisher,
    private val sessionRuntime: AuthSessionRuntime,
    private val eventReducer: AuthUserEventReducer,
) {
    private val unauthorizedCheckMutex = Mutex()

    suspend fun refreshPipelineSession(expectedAccount: AccountScope.Token): PipelineSession? =
        sessionRuntime.transition {
            val session = state.beginPipelineTransition(expectedAccount) ?: return@transition null
            publisher.fetchAuthToken(session)
            state.pipelineSession?.takeIf { pipeline -> pipeline.account == expectedAccount }
        }

    suspend fun resynchronizePipelineState(expectedAccount: AccountScope.Token) {
        sessionRuntime.transition {
            val session = state.beginPipelineTransition(expectedAccount) ?: return@transition
            when (val check = remote.checkSession()) {
                is SessionCheck.Active -> {
                    if (check.user.id != expectedAccount.ownerUserId) {
                        publisher.endSessionIfCurrent(session)
                    } else {
                        state.withCurrent(session) {
                            currentUser = check.user
                            authState.value = AuthState.LoggedIn(check.user)
                        }
                    }
                }

                is SessionCheck.TwoFactorRequired -> state.withCurrent(session) {
                    authState.value = AuthState.RequiresTwoFactor(check.methods)
                }

                is SessionCheck.Rejected -> publisher.endSessionIfCurrent(session)

                is SessionCheck.Inconclusive -> error(check.message)
            }
        }
    }

    fun handleEvent(event: PipelineEvent, token: AccountScope.Token) {
        state.mutateForAccount(token) {
            currentUser?.let { current ->
                eventReducer.reduce(event, current)?.let { updated ->
                    currentUser = updated
                    authState.value = AuthState.LoggedIn(updated)
                }
            }
        }
    }

    suspend fun handleUnauthorizedSignal() {
        unauthorizedCheckMutex.withLock {
            // Wait for any auth request that currently owns the cookie jar, then
            // retain ownership until its cookies and state outcome are applied.
            sessionRuntime.transition {
                val session = state.capture()
                val hasPersistedArtifacts = state.hasRuntimeSession || cookies.hasAuthCookie()
                if (hasPersistedArtifacts) applyUnauthorizedCheck(session, remote.checkSession())
            }
        }
    }

    private suspend fun applyUnauthorizedCheck(session: SessionToken, check: SessionCheck) {
        when (check) {
            is SessionCheck.Active -> state.withCurrent(session) {
                currentUser = check.user
                authState.value = AuthState.LoggedIn(check.user)
            }

            is SessionCheck.TwoFactorRequired -> state.withCurrent(session) {
                authState.value = AuthState.RequiresTwoFactor(check.methods)
            }

            is SessionCheck.Inconclusive -> Unit

            is SessionCheck.Rejected -> publisher.endSessionIfCurrent(session)
        }
    }
}

/** Reduces only current-user pipeline payloads, treating malformed external shapes as no-ops. */
internal class AuthUserEventReducer(private val json: Json) {
    fun reduce(event: PipelineEvent, current: CurrentUser): CurrentUser? = when (event) {
        is PipelineEvent.UserUpdate -> applyUserUpdate(event, current)
        is PipelineEvent.UserLocation -> applyUserLocation(event, current)
        else -> null
    }

    private fun applyUserUpdate(event: PipelineEvent.UserUpdate, current: CurrentUser): CurrentUser? {
        val content = event.content as? JsonObject
        val userPatch = content?.get("user") as? JsonObject
        return if (userPatch == null) {
            null
        } else {
            runCatching {
                val currentJson = json.encodeToJsonElement(CurrentUser.serializer(), current).jsonObject
                json.decodeFromJsonElement(
                    CurrentUser.serializer(),
                    JsonObject(currentJson + userPatch),
                )
            }.getOrElse { failure ->
                if (failure !is Exception) throw failure
                null
            }
        }
    }

    private fun applyUserLocation(event: PipelineEvent.UserLocation, current: CurrentUser): CurrentUser? {
        val content = event.content as? JsonObject
        // Some pipeline payloads use the historical lowercase `userid` key.
        val userId = content?.stringOrNull("userId") ?: content?.stringOrNull("userid")
        val location = content?.stringOrNull("location")
        val travelingToLocation = content?.stringOrNull("travelingToLocation")
        return if (userId == current.id && location != null) {
            current.copy(location = location, travelingToLocation = travelingToLocation)
        } else {
            null
        }
    }
}

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.contentOrNull
