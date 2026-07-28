package com.vrcx.android.data.repository

import android.content.Context
import com.vrcx.android.data.api.AuthApi
import com.vrcx.android.data.api.AuthEvent
import com.vrcx.android.data.api.AuthEventBus
import com.vrcx.android.data.api.AuthInterceptor
import com.vrcx.android.data.api.CookieJarImpl
import com.vrcx.android.data.api.RequestDeduplicator
import com.vrcx.android.data.api.model.CurrentUser
import com.vrcx.android.data.api.model.TwoFactorAuthRequest
import com.vrcx.android.data.preferences.VrcxPreferences
import com.vrcx.android.data.websocket.PipelineEvent
import com.vrcx.android.service.WebSocketForegroundService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

sealed class AuthState {
    data object NotLoggedIn : AuthState()
    data object LoggingIn : AuthState()
    data class RequiresTwoFactor(
        val methods: List<String>,
        val isVerifying: Boolean = false,
        val errorMessage: String? = null,
    ) : AuthState()
    data class LoggedIn(val user: CurrentUser) : AuthState()
    data class Error(val message: String) : AuthState()
}

@Singleton
class AuthRepository @Inject constructor(
    private val authApi: AuthApi,
    private val authInterceptor: AuthInterceptor,
    private val cookieJar: CookieJarImpl,
    private val preferences: VrcxPreferences,
    private val json: Json,
    private val dedup: RequestDeduplicator,
    private val favoriteRepository: FavoriteRepository,
    @ApplicationContext private val context: Context,
    authEventBus: AuthEventBus? = null,
) {
    private val _authState = MutableStateFlow<AuthState>(AuthState.NotLoggedIn)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private var _currentUser: CurrentUser? = null
    val currentUser: CurrentUser? get() = _currentUser

    private var _authToken: String? = null
    val authToken: String? get() = _authToken

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // App start and the websocket service both resume on launch. Serialize them
    // so a single cold start can't run two resumes — each of which calls
    // onLoginSuccess() and wipes account-scoped runtime state the other filled.
    private val resumeMutex = Mutex()

    @Inject lateinit var avatarRepositoryProvider: Provider<AvatarRepository>
    @Inject lateinit var friendRepositoryProvider: Provider<FriendRepository>
    @Inject lateinit var galleryRepositoryProvider: Provider<GalleryRepository>
    @Inject lateinit var groupRepositoryProvider: Provider<GroupRepository>
    @Inject lateinit var instanceRepositoryProvider: Provider<InstanceRepository>
    @Inject lateinit var moderationRepositoryProvider: Provider<ModerationRepository>
    @Inject lateinit var notificationRepositoryProvider: Provider<NotificationRepository>
    @Inject lateinit var userRepositoryProvider: Provider<UserRepository>
    @Inject lateinit var worldRepositoryProvider: Provider<WorldRepository>

    init {
        // Collect unauthorized signals from ErrorInterceptor so a 401 on any
        // request immediately transitions the app back to NotLoggedIn without
        // waiting for the user to trigger an auth-aware code path.
        authEventBus?.let { bus ->
            scope.launch {
                bus.events.collect { event ->
                    when (event) {
                        AuthEvent.Unauthorized -> handleUnauthorizedSignal()
                    }
                }
            }
        }
    }

    suspend fun login(username: String, password: String) {
        try {
            _authState.value = AuthState.LoggingIn
            authInterceptor.setBasicAuth(username, password)

            val response = authApi.getCurrentUser()
            val jsonObj = response.jsonObject

            // Check if 2FA is required
            if (jsonObj.containsKey("requiresTwoFactorAuth")) {
                val methods = jsonObj["requiresTwoFactorAuth"]?.jsonArray
                    ?.map { it.jsonPrimitive.content }
                    ?: emptyList()
                authInterceptor.clearBasicAuth()
                _authState.value = AuthState.RequiresTwoFactor(methods)
                return
            }

            // Full login successful
            val user = json.decodeFromJsonElement(CurrentUser.serializer(), response)
            onLoginSuccess(user)
        } catch (e: Exception) {
            authInterceptor.clearBasicAuth()
            setErrorUnlessLoggedOut(e.message ?: "Login failed")
        }
    }

    suspend fun resendEmailOtp(username: String, password: String) {
        clearAccountRuntimeState()
        clearAuthSession()
        _authState.value = AuthState.NotLoggedIn
        login(username, password)
    }

    suspend fun verifyTotp(code: String) {
        val phase = _authState.value as? AuthState.RequiresTwoFactor ?: return
        try {
            _authState.value = phase.copy(isVerifying = true, errorMessage = null)
            val digitsOnly = code.filter(Char::isDigit)
            // Recovery codes (OTP) are 8 digits and need a hyphen at position 4
            val formattedCode = if (digitsOnly.length == 8) {
                "${digitsOnly.substring(0, 4)}-${digitsOnly.substring(4)}"
            } else {
                digitsOnly.ifEmpty { code }
            }
            val result = if (digitsOnly.length == 8) {
                authApi.verifyOtp(TwoFactorAuthRequest(formattedCode))
            } else {
                authApi.verifyTotp(TwoFactorAuthRequest(formattedCode))
            }
            if (result.verified) {
                fetchCurrentUser()
            } else {
                setTwoFactorError("Verification failed")
            }
        } catch (e: Exception) {
            setTwoFactorError(e.message ?: "Verification failed")
        }
    }

    suspend fun verifyEmailOtp(code: String) {
        val phase = _authState.value as? AuthState.RequiresTwoFactor ?: return
        try {
            _authState.value = phase.copy(isVerifying = true, errorMessage = null)
            val result = authApi.verifyEmailOtp(TwoFactorAuthRequest(code))
            if (result.verified) {
                fetchCurrentUser()
            } else {
                setTwoFactorError("Verification failed")
            }
        } catch (e: Exception) {
            setTwoFactorError(e.message ?: "Verification failed")
        }
    }

    suspend fun fetchCurrentUser() {
        when (val check = checkSession()) {
            is SessionCheck.Active -> onLoginSuccess(check.user)
            is SessionCheck.TwoFactorRequired ->
                _authState.value = AuthState.RequiresTwoFactor(check.methods)
            is SessionCheck.Rejected -> setErrorUnlessLoggedOut(check.message)
            is SessionCheck.Inconclusive -> setErrorUnlessLoggedOut(check.message)
        }
    }

    suspend fun fetchAuthToken() {
        try {
            val token = authApi.getAuthToken()
            _authToken = token.token
        } catch (e: Exception) {
            // Token fetch failed, WebSocket won't connect
        }
    }

    suspend fun ensureSessionReady(): Boolean {
        if (_currentUser != null && !_authToken.isNullOrBlank() && _authState.value is AuthState.LoggedIn) {
            return true
        }

        if (_currentUser == null) {
            tryResumeSession()
        }

        if (_currentUser != null && _authToken.isNullOrBlank()) {
            fetchAuthToken()
        }

        return _currentUser != null && !_authToken.isNullOrBlank() && _authState.value is AuthState.LoggedIn
    }

    /** True while a stored cookie session exists that a resume could still revive. */
    fun hasResumableSession(): Boolean = cookieJar.getAuthCookie() != null

    /**
     * Revive the stored cookie session on app/service start.
     *
     * A cold start after the app has been idle routinely lands while the radio
     * is still waking up or the device is leaving Doze, so the first request can
     * fail with no network at all. That is not an expired session — retry a few
     * times before giving up, and when it still doesn't resolve leave the stored
     * cookies alone so the next launch (or a retry from the login screen) can
     * pick the session back up. Only a server-side rejection ends the session.
     */
    suspend fun tryResumeSession() = resumeMutex.withLock {
        if (_authState.value is AuthState.LoggedIn) return@withLock
        if (!hasResumableSession()) return@withLock
        _authState.value = AuthState.LoggingIn

        var lastFailure: SessionCheck.Inconclusive? = null
        for (delayMs in RESUME_RETRY_DELAYS_MS) {
            if (delayMs > 0) delay(delayMs)
            when (val check = checkSession()) {
                is SessionCheck.Active -> {
                    onLoginSuccess(check.user)
                    return@withLock
                }
                is SessionCheck.TwoFactorRequired -> {
                    // The cookie is still good; VRChat just wants the second
                    // factor again. Keep the session so verify2fa can use it.
                    _authState.value = AuthState.RequiresTwoFactor(check.methods)
                    return@withLock
                }
                is SessionCheck.Rejected -> {
                    endSession()
                    return@withLock
                }
                is SessionCheck.Inconclusive -> lastFailure = check
            }
        }
        setErrorUnlessLoggedOut(lastFailure?.message ?: UNREACHABLE_MESSAGE)
    }

    suspend fun logout() {
        // Best-effort server invalidation before local cleanup so a stolen cookie
        // can't outlive the user's intent. Network failure must not block sign-out
        // (the user might be logging out specifically because they have no network).
        try {
            authApi.logout()
        } catch (_: Exception) {
            // Swallow: local state still gets cleared below.
        }
        clearAccountRuntimeState()
        clearAuthSession()
        // Stop the websocket service so every logout path — explicit sign-out
        // from Profile/Settings, interceptor-driven 401, etc. — tears down the
        // background socket + persistent notification. Callers no longer need
        // to remember to do this themselves.
        WebSocketForegroundService.stop(context)
        _authState.value = AuthState.NotLoggedIn
    }

    fun handleEvent(event: PipelineEvent) {
        when (event) {
            is PipelineEvent.UserUpdate -> {
                val userPatch = event.content?.jsonObject?.get("user")?.jsonObject ?: return
                val current = _currentUser ?: return
                try {
                    val currentJson = json.encodeToJsonElement(CurrentUser.serializer(), current).jsonObject
                    val user = json.decodeFromJsonElement(
                        CurrentUser.serializer(),
                        JsonObject(currentJson + userPatch),
                    )
                    _currentUser = user
                    _authState.value = AuthState.LoggedIn(user)
                } catch (_: Exception) {}
            }
            is PipelineEvent.UserLocation -> {
                val content = event.content?.jsonObject ?: return
                val userId = content["userId"]?.jsonPrimitive?.content ?: return
                val current = _currentUser ?: return
                if (userId != current.id) return
                val location = content["location"]?.jsonPrimitive?.content
                val travelingToLocation = content["travelingToLocation"]?.jsonPrimitive?.content
                _currentUser = current.copy(
                    location = location,
                    travelingToLocation = travelingToLocation,
                )
                _authState.value = AuthState.LoggedIn(_currentUser!!)
            }
            else -> {}
        }
    }

    private suspend fun onLoginSuccess(user: CurrentUser) {
        authInterceptor.clearBasicAuth()
        clearAccountRuntimeState()
        _currentUser = user
        _authState.value = AuthState.LoggedIn(user)
        preferences.setLastUserId(user.id)
        fetchAuthToken()
    }

    /**
     * A 401 on some unrelated request is only a hint. Re-check `auth/user` and
     * act on what the *server* says: keep the session unless it is affirmatively
     * rejected. A network failure, timeout, 429 or 5xx during that re-check
     * proves nothing — discarding the cookies there would turn a momentary
     * connectivity blip (Doze, a Wi-Fi/cellular handover while the app sits in
     * the background) into a permanent sign-out.
     */
    internal suspend fun handleUnauthorizedSignal() {
        if (!hasPersistedSessionArtifacts()) {
            return
        }
        when (val check = checkSession()) {
            is SessionCheck.Active -> {
                _currentUser = check.user
                _authState.value = AuthState.LoggedIn(check.user)
            }
            is SessionCheck.TwoFactorRequired -> {
                // The cookie survives; only the second factor lapsed. Clearing
                // cookies here would strip the credential that verify2fa needs.
                _authState.value = AuthState.RequiresTwoFactor(check.methods)
            }
            is SessionCheck.Inconclusive -> Unit
            is SessionCheck.Rejected -> {
                endSession()
            }
        }
    }

    private suspend fun endSession() {
        clearAccountRuntimeState()
        clearAuthSession()
        WebSocketForegroundService.stop(context)
        _authState.value = AuthState.NotLoggedIn
    }

    private fun hasPersistedSessionArtifacts(): Boolean {
        return _currentUser != null ||
            _authToken != null ||
            cookieJar.getAuthCookie() != null
    }

    /** Outcome of asking VRChat whether the stored session is still usable. */
    private sealed interface SessionCheck {
        data class Active(val user: CurrentUser) : SessionCheck
        data class TwoFactorRequired(val methods: List<String>) : SessionCheck
        /** The server rejected the credentials — the session is gone for good. */
        data class Rejected(val message: String) : SessionCheck
        /** We never got an answer. The session may well still be valid. */
        data class Inconclusive(val message: String) : SessionCheck
    }

    private suspend fun checkSession(): SessionCheck {
        return try {
            val response = authApi.getCurrentUser()
            val jsonObj = response.jsonObject
            if (jsonObj.containsKey("requiresTwoFactorAuth")) {
                val methods = jsonObj["requiresTwoFactorAuth"]?.jsonArray
                    ?.map { it.jsonPrimitive.content }
                    ?: emptyList()
                SessionCheck.TwoFactorRequired(methods)
            } else {
                SessionCheck.Active(json.decodeFromJsonElement(CurrentUser.serializer(), response))
            }
        } catch (e: HttpException) {
            if (e.code() == 401 || e.code() == 403) {
                SessionCheck.Rejected(e.message ?: "Session expired")
            } else {
                SessionCheck.Inconclusive(e.message ?: UNREACHABLE_MESSAGE)
            }
        } catch (e: CancellationException) {
            // The deduplicator cancels in-flight work on logout/account switch.
            // Swallow rather than rethrow: this runs inside the long-lived
            // AuthEvent collector, and cancelling that job would stop every
            // future unauthorized signal from being handled.
            SessionCheck.Inconclusive(e.message ?: UNREACHABLE_MESSAGE)
        } catch (e: Exception) {
            SessionCheck.Inconclusive(e.message ?: UNREACHABLE_MESSAGE)
        }
    }

    private fun clearAuthSession() {
        clearInjectedRuntimeState()
        _currentUser = null
        _authToken = null
        authInterceptor.clearBasicAuth()
        cookieJar.clearAll()
        dedup.clearCache()
    }

    private suspend fun clearAccountRuntimeState() {
        favoriteRepository.clearRuntimeState()
        clearInjectedRuntimeState()
    }

    private fun clearInjectedRuntimeState() {
        if (::avatarRepositoryProvider.isInitialized) avatarRepositoryProvider.get().clearRuntimeState()
        if (::friendRepositoryProvider.isInitialized) friendRepositoryProvider.get().clearRuntimeState()
        if (::galleryRepositoryProvider.isInitialized) galleryRepositoryProvider.get().clearRuntimeState()
        if (::groupRepositoryProvider.isInitialized) groupRepositoryProvider.get().clearRuntimeState()
        if (::instanceRepositoryProvider.isInitialized) instanceRepositoryProvider.get().clearRuntimeState()
        if (::moderationRepositoryProvider.isInitialized) moderationRepositoryProvider.get().clearRuntimeState()
        if (::notificationRepositoryProvider.isInitialized) notificationRepositoryProvider.get().clearRuntimeState()
        if (::userRepositoryProvider.isInitialized) userRepositoryProvider.get().clearCache()
        if (::worldRepositoryProvider.isInitialized) worldRepositoryProvider.get().clearRuntimeState()
    }

    /**
     * Set [AuthState.Error] from a request-coroutine catch block, unless the
     * interceptor-driven unauthorized collector has already cleaned up and
     * transitioned the app to [AuthState.NotLoggedIn]. Uses [MutableStateFlow.update]
     * for an atomic compare-and-set so the two coroutines can't interleave in a
     * way that leaves the final state as `Error` when we meant `NotLoggedIn`.
     *
     * Without this guard, a 401 during session resume triggers both:
     *   - fetchCurrentUser()'s catch on the request coroutine → `Error`
     *   - the Unauthorized collector on its own coroutine     → `NotLoggedIn`
     * and whichever wrote last wins.
     */
    private fun setErrorUnlessLoggedOut(message: String) {
        _authState.update { current ->
            if (current is AuthState.NotLoggedIn) current
            else AuthState.Error(message)
        }
    }

    private fun setTwoFactorError(message: String) {
        _authState.update { current ->
            if (current is AuthState.RequiresTwoFactor) {
                current.copy(isVerifying = false, errorMessage = message)
            } else {
                current
            }
        }
    }

    private companion object {
        /**
         * Backoff between session-resume attempts. The first attempt is
         * immediate; the later ones cover a radio that is still associating
         * after the process was killed in the background.
         */
        val RESUME_RETRY_DELAYS_MS = longArrayOf(0L, 2_000L, 5_000L)
        const val UNREACHABLE_MESSAGE = "Couldn't reach VRChat"
    }
}
