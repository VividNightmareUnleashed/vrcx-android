package com.vrcx.android.data.repository

import com.vrcx.android.data.api.AuthApi
import com.vrcx.android.data.api.AuthEventBus
import com.vrcx.android.data.api.CookieJarImpl
import com.vrcx.android.data.api.model.CurrentUser
import com.vrcx.android.data.preferences.VrcxPreferences
import com.vrcx.android.data.security.SecureSecretsStore
import com.vrcx.android.data.websocket.PipelineEvent
import com.vrcx.android.di.DefaultDispatcher
import com.vrcx.android.di.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json

sealed class AuthState {
    data object NotLoggedIn : AuthState()
    data object LoggingIn : AuthState()
    data class RequiresTwoFactor(
        val methods: List<String>,
        val verification: TwoFactorVerification = TwoFactorVerification.Idle,
    ) : AuthState()
    data class LoggedIn(val user: CurrentUser) : AuthState()
    data class Error(val message: String) : AuthState()
}

/** Where a two-factor challenge stands. A spinner and a stale error cannot both be showing. */
sealed interface TwoFactorVerification {
    data object Idle : TwoFactorVerification
    data object InProgress : TwoFactorVerification
    data class Failed(val message: String) : TwoFactorVerification
}

internal data class PipelineSession(val authToken: String, val account: AccountScope.Token)

@Singleton
class AuthRepository private constructor(private val owner: AuthRepositoryOwner) :
    AuthLoginActions by owner.loginActions,
    AuthSessionActions by owner.sessionActions {

    @Inject
    internal constructor(
        authApi: AuthApi,
        cookieJar: CookieJarImpl,
        preferences: VrcxPreferences,
        secureSecretsStore: SecureSecretsStore,
        json: Json,
        sessionRuntime: AuthSessionRuntime,
        @DefaultDispatcher defaultDispatcher: CoroutineDispatcher,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
        authEventBus: AuthEventBus? = null,
    ) : this(
        AuthRepositoryOwner(
            authApi = authApi,
            cookieJar = cookieJar,
            logoutStorage = AuthLogoutStorage(
                secureSecretsStore = secureSecretsStore,
                preferences = preferences,
                ioDispatcher = ioDispatcher,
            ),
            json = json,
            sessionRuntime = sessionRuntime,
            ioDispatcher = ioDispatcher,
        ),
    ) {
        owner.start(defaultDispatcher, authEventBus)
    }

    val authState: StateFlow<AuthState> = owner.state.authState
    val storageError: StateFlow<String?> = owner.cookies.storageError
    val currentUser: CurrentUser? get() = owner.state.currentUser
    val authToken: String? get() = owner.state.authToken

    internal fun pipelineSession(): PipelineSession? = owner.state.pipelineSession

    internal suspend fun refreshPipelineSession(expectedAccount: AccountScope.Token): PipelineSession? =
        owner.pipeline.refreshPipelineSession(expectedAccount)

    internal suspend fun resynchronizePipelineState(expectedAccount: AccountScope.Token) {
        owner.pipeline.resynchronizePipelineState(expectedAccount)
    }

    fun handleEvent(event: PipelineEvent, token: AccountScope.Token) {
        owner.pipeline.handleEvent(event, token)
    }

    internal fun handleEvent(event: PipelineEvent) {
        handleEvent(event, owner.sessionRuntime.currentAccount())
    }

    internal suspend fun handleUnauthorizedSignal() {
        owner.pipeline.handleUnauthorizedSignal()
    }

    fun dismissStorageError() {
        owner.cookies.dismissError()
    }
}
