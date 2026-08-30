package com.vrcx.android.data.repository

import com.vrcx.android.data.api.AuthApi
import com.vrcx.android.data.api.AuthEvent
import com.vrcx.android.data.api.AuthEventBus
import com.vrcx.android.data.api.CookieJarImpl
import com.vrcx.android.data.api.CookieStorageStatus
import com.vrcx.android.data.api.basicAuthorization
import com.vrcx.android.data.api.model.CurrentUser
import com.vrcx.android.data.api.model.TwoFactorAuthRequest
import com.vrcx.android.data.api.model.TwoFactorAuthResponse
import com.vrcx.android.data.preferences.VrcxPreferences
import com.vrcx.android.data.security.SecureSecretsStore
import com.vrcx.android.data.util.runCatchingCancellable
import com.vrcx.android.data.websocket.PipelineEvent
import com.vrcx.android.di.DefaultDispatcher
import com.vrcx.android.di.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException

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

/** Where a two-factor challenge stands. A spinner and a stale error can't both be showing. */
sealed interface TwoFactorVerification {
    data object Idle : TwoFactorVerification
    data object InProgress : TwoFactorVerification
    data class Failed(val message: String) : TwoFactorVerification
}

internal data class PipelineSession(val authToken: String, val account: AccountScope.Token)

@Singleton
class AuthRepository @Inject internal constructor(
    private val authApi: AuthApi,
    private val cookieJar: CookieJarImpl,
    private val preferences: VrcxPreferences,
    private val secureSecretsStore: SecureSecretsStore,
    private val json: Json,
    private val sessionRuntime: AuthSessionRuntime,
    @DefaultDispatcher defaultDispatcher: CoroutineDispatcher,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    authEventBus: AuthEventBus? = null,
) {
    private val _authState = MutableStateFlow<AuthState>(AuthState.NotLoggedIn)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _storageError = MutableStateFlow(
        cookieStorageError(cookieJar.storageStatus.value),
    )
    val storageError: StateFlow<String?> = _storageError.asStateFlow()

    private val sessionLock = Any()
    private var _currentUser: CurrentUser? = null
    val currentUser: CurrentUser? get() = synchronized(sessionLock) { _currentUser }

    private var _authToken: String? = null
    val authToken: String? get() = synchronized(sessionLock) { _authToken }

    internal fun pipelineSession(): PipelineSession? = synchronized(sessionLock) {
        if (_authState.value !is AuthState.LoggedIn) return@synchronized null
        val userId = _currentUser?.id ?: return@synchronized null
        val token = _authToken ?: return@synchronized null
        val account = sessionRuntime.currentAccount()
        if (account.ownerUserId == userId) PipelineSession(token, account) else null
    }

    private val scope = CoroutineScope(SupervisorJob() + defaultDispatcher)

    private var sessionGeneration = 0L

    private val unauthorizedCheckMutex = Mutex()

    init {
        scope.launch {
            cookieJar.storageStatus.collect { status ->
                cookieStorageError(status)?.let { message ->
                    if (cookieJar.storageStatus.value == status) {
                        _storageError.update { current -> current ?: message }
                    }
                }
            }
        }
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
        runSessionTransition { session -> login(session, username, password) }
    }

    private suspend fun login(session: SessionToken, username: String, password: String) {
        // A stored auth cookie can outrank the Basic header on auth/user, and the
        // login screen is reachable with one still in the jar (a resume that only
        // failed to reach the server keeps it) — so signing in as somebody else
        // would resume the previous account. Drop it for the attempt, but keep a
        // copy: an unreachable server must not cost the user a live session.
        val storedCookies = withContext(ioDispatcher) { cookieJar.snapshot() }
        var sessionCommitted = false
        try {
            if (!publishIfCurrent(session) { _authState.value = AuthState.LoggingIn }) return
            withContext(ioDispatcher) { cookieJar.clearAll() }
            if (!isSessionCurrent(session)) return

            val response = authApi.loginWithBasicAuth(basicAuthorization(username, password))
            val jsonObj = response.jsonObject

            // Check if 2FA is required
            if (jsonObj.containsKey("requiresTwoFactorAuth")) {
                val methods = jsonObj["requiresTwoFactorAuth"]?.jsonArray
                    ?.map { it.jsonPrimitive.content }
                    .orEmpty()
                withCurrentSession(session) {
                    _authState.value = AuthState.RequiresTwoFactor(methods)
                }
                return
            }

            // Full login successful
            val user = json.decodeFromJsonElement(CurrentUser.serializer(), response)
            if (!commitAuthenticatedSession(session)) return
            sessionCommitted = publishLoginSuccess(session, user)
            if (sessionCommitted) fetchAuthToken(session)
        } catch (e: CancellationException) {
            if (!sessionCommitted) {
                restoreCookiesIfCurrent(session, storedCookies)
            }
            throw e
        } catch (e: Exception) {
            if (!sessionCommitted && !isCredentialRejection(e)) {
                restoreCookiesIfCurrent(session, storedCookies)
            }
            setErrorUnlessLoggedOut(session, e.message ?: "Login failed")
        }
    }

    /** A 401/403 is the server saying no. Anything else leaves the stored session's fate unknown. */
    private fun isCredentialRejection(failure: Exception): Boolean =
        failure is HttpException && (failure.code() == 401 || failure.code() == 403)

    suspend fun resendEmailOtp(username: String, password: String) {
        runSessionTransition { session ->
            if (!resetSessionIfCurrent(session)) return@runSessionTransition
            login(session, username, password)
        }
    }

    suspend fun verifyTotp(code: String) {
        // Recovery codes are 8 alphanumeric characters — VRChat renders them as
        // 4+4 with a separator, and they carry letters. Keeping only digits would
        // mangle them into a fragment the authenticator endpoint rejects, so the
        // whole decision is made once here, on the alphanumeric form.
        val normalized = code.filter(Char::isLetterOrDigit).ifEmpty { code }
        val isRecoveryCode = normalized.length == RECOVERY_CODE_LENGTH
        val submittedCode = if (isRecoveryCode) {
            "${normalized.substring(0, 4)}-${normalized.substring(4)}"
        } else {
            normalized
        }
        runSessionTransition { session ->
            verifyTwoFactor(session) {
                if (isRecoveryCode) {
                    authApi.verifyOtp(TwoFactorAuthRequest(submittedCode))
                } else {
                    authApi.verifyTotp(TwoFactorAuthRequest(submittedCode))
                }
            }
        }
    }

    // Email codes go to the dedicated email OTP endpoint exactly as typed.
    suspend fun verifyEmailOtp(code: String) {
        runSessionTransition { session ->
            verifyTwoFactor(session) {
                authApi.verifyEmailOtp(TwoFactorAuthRequest(code))
            }
        }
    }

    private suspend fun verifyTwoFactor(session: SessionToken, submit: suspend () -> TwoFactorAuthResponse) {
        val phase = withCurrentSession(session) {
            _authState.value as? AuthState.RequiresTwoFactor
        } ?: return
        try {
            if (!publishIfCurrent(session) {
                    _authState.value = phase.copy(verification = TwoFactorVerification.InProgress)
                }
            ) {
                return
            }
            if (submit().verified) {
                fetchCurrentUser(session, shouldCommitAuthenticatedSession = true)
            } else {
                setTwoFactorError(session, "Verification failed")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setTwoFactorError(session, e.message ?: "Verification failed")
        }
    }

    suspend fun fetchCurrentUser() {
        runSessionTransition { session -> fetchCurrentUser(session) }
    }

    private suspend fun fetchCurrentUser(session: SessionToken, shouldCommitAuthenticatedSession: Boolean = false) {
        when (val check = checkSession()) {
            is SessionCheck.Active -> {
                if (shouldCommitAuthenticatedSession && !commitAuthenticatedSession(session)) return
                onLoginSuccess(session, check.user)
            }

            is SessionCheck.TwoFactorRequired ->
                withCurrentSession(session) {
                    _authState.value = AuthState.RequiresTwoFactor(check.methods)
                }

            is SessionCheck.Rejected -> setErrorUnlessLoggedOut(session, check.message)

            is SessionCheck.Inconclusive -> setErrorUnlessLoggedOut(session, check.message)
        }
    }

    suspend fun fetchAuthToken() {
        runSessionTransition(::fetchAuthToken)
    }

    internal suspend fun refreshPipelineSession(expectedAccount: AccountScope.Token): PipelineSession? =
        sessionRuntime.transition {
            val session = beginPipelineTransition(expectedAccount) ?: return@transition null
            fetchAuthToken(session)
            pipelineSession()?.takeIf { it.account == expectedAccount }
        }

    internal suspend fun resynchronizePipelineState(expectedAccount: AccountScope.Token) {
        sessionRuntime.transition {
            val session = beginPipelineTransition(expectedAccount) ?: return@transition
            when (val check = checkSession()) {
                is SessionCheck.Active -> {
                    if (check.user.id != expectedAccount.ownerUserId) {
                        endSessionIfCurrent(session)
                    } else {
                        withCurrentSession(session) {
                            _currentUser = check.user
                            _authState.value = AuthState.LoggedIn(check.user)
                        }
                    }
                }

                is SessionCheck.TwoFactorRequired -> {
                    withCurrentSession(session) {
                        _authState.value = AuthState.RequiresTwoFactor(check.methods)
                    }
                }

                is SessionCheck.Rejected -> endSessionIfCurrent(session)

                is SessionCheck.Inconclusive -> error(check.message)
            }
        }
    }

    suspend fun ensureSessionReady(): Boolean {
        if (isSessionReady()) return true

        var ready = false
        runSessionTransition { session ->
            if (isSessionReady()) {
                ready = true
                return@runSessionTransition
            }

            if (_currentUser == null) {
                tryResumeSession(session)
            }

            if (isSessionCurrent(session) && _currentUser != null && _authToken.isNullOrBlank()) {
                fetchAuthToken(session)
            }

            ready = withCurrentSession(session) { isSessionReady() } ?: false
        }
        return ready
    }

    private fun isSessionReady(): Boolean = synchronized(sessionLock) {
        _currentUser != null && !_authToken.isNullOrBlank() &&
            _authState.value is AuthState.LoggedIn
    }

    /** True while a stored cookie session exists that a resume could still revive. */
    suspend fun hasResumableSession(): Boolean = withContext(ioDispatcher) {
        cookieJar.getAuthCookie() != null
    }

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
    suspend fun tryResumeSession() {
        runSessionTransition(::tryResumeSession)
    }

    private suspend fun tryResumeSession(session: SessionToken) {
        if (_authState.value is AuthState.LoggedIn) return
        if (!hasResumableSession()) return
        if (!publishIfCurrent(session) { _authState.value = AuthState.LoggingIn }) return

        var lastFailure: SessionCheck.Inconclusive? = null
        for (delayMs in RESUME_RETRY_DELAYS_MS) {
            if (!isSessionCurrent(session)) return
            if (delayMs > 0) delay(delayMs)
            if (!isSessionCurrent(session)) return
            when (val check = checkSession()) {
                is SessionCheck.Active -> {
                    onLoginSuccess(session, check.user)
                    return
                }

                is SessionCheck.TwoFactorRequired -> {
                    // The cookie is still good; VRChat just wants the second
                    // factor again. Keep the session so verify2fa can use it.
                    withCurrentSession(session) {
                        _authState.value = AuthState.RequiresTwoFactor(check.methods)
                    }
                    return
                }

                is SessionCheck.Rejected -> {
                    endSessionIfCurrent(session)
                    return
                }

                is SessionCheck.Inconclusive -> lastFailure = check
            }
        }
        setErrorUnlessLoggedOut(session, lastFailure?.message ?: UNREACHABLE_MESSAGE)
    }

    suspend fun logout() {
        // Best-effort server invalidation before local cleanup so a stolen cookie
        // can't outlive the user's intent. Network failure must not block sign-out
        // (the user might be logging out specifically because they have no network).
        val session = beginSessionTransition()
        var cancellation: CancellationException? = null
        try {
            sessionRuntime.transition {
                if (!isSessionCurrent(session)) return@transition
                try {
                    authApi.logout()
                } catch (e: CancellationException) {
                    cancellation = e
                } catch (_: Exception) {
                    // Local state still gets cleared below.
                }
                withContext(NonCancellable) {
                    completeExplicitLogout(session)
                }
            }
        } catch (e: CancellationException) {
            // Cancellation while queued behind another cookie-bearing auth request
            // must not turn sign-out into a no-op. A newer login still wins because
            // its generation makes this conditional cleanup stale.
            cancellation = e
            withContext(NonCancellable) {
                sessionRuntime.transition {
                    completeExplicitLogout(session)
                }
            }
        }
        cancellation?.let { throw it }
    }

    private suspend fun completeExplicitLogout(session: SessionToken) {
        if (!isSessionCurrent(session)) return
        sessionRuntime.publishExplicitLogout()
        try {
            forgetStoredSecrets()
        } finally {
            endSessionIfCurrent(session)
        }
    }

    /**
     * Signing out is a promise that the account is off the device. The cookies are
     * revoked above, but a remembered password is reusable: leaving it behind means
     * the next person to open the app sees the username and can reveal the password
     * with one tap, and auto-login signs straight back in. Only the explicit
     * sign-out path does this — an involuntary session end has to leave remember-me
     * intact so a background 401 doesn't cost the user their stored credentials.
     */
    private suspend fun forgetStoredSecrets() = withContext(ioDispatcher) {
        var cleanupFailed = false
        val encryptedSecretsCleared = try {
            secureSecretsStore.clearAll()
        } catch (_: Exception) {
            false
        }
        if (!encryptedSecretsCleared) cleanupFailed = true

        if (encryptedSecretsCleared) {
            try {
                if (!cookieJar.completeLogoutAfterSecretsDeleted()) cleanupFailed = true
            } catch (_: Exception) {
                cleanupFailed = true
            }
        }
        try {
            preferences.clearLegacySavedCredentials()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            cleanupFailed = true
        }
        if (cleanupFailed) {
            reportStorageError(LOGOUT_STORAGE_ERROR)
        }
    }

    fun handleEvent(event: PipelineEvent, token: AccountScope.Token) {
        synchronized(sessionLock) {
            if (token.ownerUserId.isEmpty() || !sessionRuntime.isAccountCurrent(token)) return
            when (event) {
                is PipelineEvent.UserUpdate -> applyUserUpdateLocked(event)
                is PipelineEvent.UserLocation -> applyUserLocationLocked(event)
                else -> {}
            }
        }
    }

    private fun applyUserUpdateLocked(event: PipelineEvent.UserUpdate) {
        val content = event.content as? JsonObject
        val userPatch = content?.get("user") as? JsonObject
        val current = _currentUser
        if (userPatch != null && current != null) {
            try {
                val currentJson = json.encodeToJsonElement(
                    CurrentUser.serializer(),
                    current,
                ).jsonObject
                val user = json.decodeFromJsonElement(
                    CurrentUser.serializer(),
                    JsonObject(currentJson + userPatch),
                )
                _currentUser = user
                _authState.value = AuthState.LoggedIn(user)
            } catch (_: Exception) {}
        }
    }

    private fun applyUserLocationLocked(event: PipelineEvent.UserLocation) {
        val content = event.content as? JsonObject
        val current = _currentUser
        // Some pipeline payloads spell the key "userid", so accept both rather
        // than silently ignoring our own location updates.
        val userId = content?.stringOrNull("userId") ?: content?.stringOrNull("userid")
        val location = content?.stringOrNull("location")
        val travelingToLocation = content?.stringOrNull("travelingToLocation")
        if (current != null && userId == current.id && location != null) {
            val updatedUser = current.copy(
                location = location,
                travelingToLocation = travelingToLocation,
            )
            _currentUser = updatedUser
            _authState.value = AuthState.LoggedIn(updatedUser)
        }
    }

    internal fun handleEvent(event: PipelineEvent) {
        handleEvent(event, sessionRuntime.currentAccount())
    }

    private suspend fun onLoginSuccess(session: SessionToken, user: CurrentUser) {
        if (publishLoginSuccess(session, user)) fetchAuthToken(session)
    }

    private suspend fun commitAuthenticatedSession(session: SessionToken): Boolean {
        val committed = try {
            withContext(ioDispatcher) { cookieJar.commitAuthenticatedSession() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            false
        }
        if (!isSessionCurrent(session)) return false

        if (!committed) {
            // The server accepted this account, but publishing it while the old
            // durable blob can still win on restart creates a split session.
            withContext(NonCancellable + ioDispatcher) { cookieJar.clearAll() }
            reportStorageError(AUTHENTICATED_SESSION_STORAGE_ERROR)
            setErrorUnlessLoggedOut(session, AUTHENTICATED_SESSION_STORAGE_ERROR)
            return false
        }

        if (cookieJar.storageStatus.value == CookieStorageStatus.LEGACY_CLEANUP_FAILED) {
            reportStorageError(COOKIE_LEGACY_CLEANUP_ERROR)
        } else {
            _storageError.value = null
        }
        return true
    }

    private suspend fun restoreCookiesIfCurrent(session: SessionToken, snapshot: Map<String, String>) {
        if (!isSessionCurrent(session)) return
        val restored = withContext(NonCancellable + ioDispatcher) {
            cookieJar.restore(snapshot)
        }
        if (isSessionCurrent(session) && !restored) {
            reportStorageError(COOKIE_RESTORE_ERROR)
        }
    }

    private fun publishLoginSuccess(session: SessionToken, user: CurrentUser): Boolean = withCurrentSession(session) {
        if (_authState.value is AuthState.NotLoggedIn) {
            false
        } else {
            clearAccountRuntimeState()
            // The token belongs to the session being replaced. Left in place, a failed
            // fetch below would let ensureSessionReady() wave the service through and
            // connect the pipeline with the previous session's token.
            _authToken = null
            _currentUser = user
            _authState.value = AuthState.LoggedIn(user)
            sessionRuntime.bindAccount(user.id)
            true
        }
    } == true

    fun dismissStorageError() {
        _storageError.value = null
    }

    private fun reportStorageError(message: String) {
        _storageError.value = message
    }

    private fun cookieStorageError(status: CookieStorageStatus): String? = when (status) {
        CookieStorageStatus.READY -> null
        CookieStorageStatus.UNREADABLE -> COOKIE_STORAGE_UNREADABLE_ERROR
        CookieStorageStatus.WRITE_FAILED -> COOKIE_STORAGE_WRITE_ERROR
        CookieStorageStatus.LEGACY_CLEANUP_FAILED -> COOKIE_LEGACY_CLEANUP_ERROR
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
        unauthorizedCheckMutex.withLock {
            // An auth request already in progress owns the shared cookie jar. Wait
            // for it to finish, then keep ownership until this response has saved
            // any cookies and its state outcome has been applied.
            sessionRuntime.transition sessionOperation@{
                val session = captureSession()
                if (!hasPersistedSessionArtifacts()) return@sessionOperation
                when (val check = checkSession()) {
                    is SessionCheck.Active -> {
                        withCurrentSession(session) {
                            _currentUser = check.user
                            _authState.value = AuthState.LoggedIn(check.user)
                        }
                    }

                    is SessionCheck.TwoFactorRequired -> {
                        // The cookie survives; only the second factor lapsed. Clearing
                        // cookies here would strip the credential that verify2fa needs.
                        withCurrentSession(session) {
                            _authState.value = AuthState.RequiresTwoFactor(check.methods)
                        }
                    }

                    is SessionCheck.Inconclusive -> Unit

                    is SessionCheck.Rejected -> endSessionIfCurrent(session)
                }
            }
        }
    }

    /**
     * Ends the session and publishes it. The websocket service watches
     * [authState] and takes its socket and ongoing notification down on
     * [AuthState.NotLoggedIn], so every session end — explicit sign-out,
     * interceptor-driven 401 — tears the background connection down without the
     * data layer knowing the service exists.
     */
    private suspend fun endSessionIfCurrent(session: SessionToken): Boolean {
        val ended = synchronized(sessionLock) {
            if (session.generation != sessionGeneration) {
                false
            } else {
                sessionGeneration++
                clearAuthStateLocked()
                true
            }
        }
        if (ended) clearSessionArtifacts()
        return ended
    }

    private suspend fun hasPersistedSessionArtifacts(): Boolean {
        val hasRuntimeSession = synchronized(sessionLock) {
            _currentUser != null || _authToken != null
        }
        return hasRuntimeSession || withContext(ioDispatcher) {
            cookieJar.getAuthCookie() != null
        }
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

    private suspend fun checkSession(): SessionCheck = try {
        val response = authApi.getCurrentUser()
        val jsonObj = response.jsonObject
        if (jsonObj.containsKey("requiresTwoFactorAuth")) {
            val methods = jsonObj["requiresTwoFactorAuth"]?.jsonArray
                ?.map { it.jsonPrimitive.content }
                .orEmpty()
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
        throw e
    } catch (e: Exception) {
        SessionCheck.Inconclusive(e.message ?: UNREACHABLE_MESSAGE)
    }

    private suspend fun resetSessionIfCurrent(session: SessionToken): Boolean {
        val reset = synchronized(sessionLock) {
            if (session.generation != sessionGeneration) {
                false
            } else {
                clearAuthStateLocked()
                true
            }
        }
        if (!reset) return false
        clearSessionArtifacts()
        return isSessionCurrent(session)
    }

    /** Caller owns [sessionLock]; durable cleanup happens later, without that monitor. */
    private fun clearAuthStateLocked() {
        clearAccountRuntimeState()
        _currentUser = null
        _authToken = null
        sessionRuntime.clearRequestCache()
        _authState.value = AuthState.NotLoggedIn
    }

    /** Once teardown is published, cancellation must not leave its cookies on disk. */
    private suspend fun clearSessionArtifacts() {
        withContext(NonCancellable + ioDispatcher) {
            cookieJar.clearAll()
        }
    }

    /**
     * The single owner of per-account repository resets. Every [AccountScoped]
     * repository is reached because registering with the scope is how a
     * repository obtains its guard in the first place — there is no separate
     * list here to forget to add to.
     */
    private fun clearAccountRuntimeState() {
        sessionRuntime.invalidateAccount()
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
    private fun setErrorUnlessLoggedOut(session: SessionToken, message: String) {
        withCurrentSession(session) {
            _authState.update { current ->
                if (current is AuthState.NotLoggedIn) {
                    current
                } else {
                    AuthState.Error(message)
                }
            }
        }
    }

    private fun setTwoFactorError(session: SessionToken, message: String) {
        withCurrentSession(session) {
            _authState.update { current ->
                if (current is AuthState.RequiresTwoFactor) {
                    current.copy(verification = TwoFactorVerification.Failed(message))
                } else {
                    current
                }
            }
        }
    }

    private suspend fun fetchAuthToken(session: SessionToken) {
        val token = runCatchingCancellable { authApi.getAuthToken().token }.getOrNull() ?: return
        withCurrentSession(session) { _authToken = token }
    }

    private suspend fun runSessionTransition(block: suspend (SessionToken) -> Unit) {
        sessionRuntime.transition {
            block(beginSessionTransition())
        }
    }

    private fun beginSessionTransition(): SessionToken = synchronized(sessionLock) {
        SessionToken(++sessionGeneration)
    }

    private fun beginPipelineTransition(expectedAccount: AccountScope.Token): SessionToken? =
        synchronized(sessionLock) {
            val account = sessionRuntime.currentAccount()
            if (account != expectedAccount || _currentUser?.id != account.ownerUserId) {
                null
            } else {
                SessionToken(++sessionGeneration)
            }
        }

    private fun captureSession(): SessionToken = synchronized(sessionLock) {
        SessionToken(sessionGeneration)
    }

    private fun isSessionCurrent(session: SessionToken): Boolean = synchronized(sessionLock) {
        session.generation == sessionGeneration
    }

    private fun publishIfCurrent(session: SessionToken, publish: () -> Unit): Boolean = synchronized(sessionLock) {
        if (session.generation != sessionGeneration) return false
        publish()
        true
    }

    private fun <T> withCurrentSession(session: SessionToken, block: () -> T): T? = synchronized(sessionLock) {
        if (session.generation != sessionGeneration) null else block()
    }

    private data class SessionToken(val generation: Long)

    private companion object {
        /** VRChat renders recovery codes as 4+4 alphanumeric characters. */
        const val RECOVERY_CODE_LENGTH = 8

        /**
         * Backoff between session-resume attempts. The first attempt is
         * immediate; the later ones cover a radio that is still associating
         * after the process was killed in the background.
         */
        val RESUME_RETRY_DELAYS_MS = longArrayOf(0L, 2_000L, 5_000L)
        const val UNREACHABLE_MESSAGE = "Couldn't reach VRChat"
        const val COOKIE_STORAGE_UNREADABLE_ERROR =
            "Saved session data couldn't be read. Sign in again to replace it safely."
        const val COOKIE_STORAGE_WRITE_ERROR =
            "Saved session data couldn't be moved to secure storage."
        const val AUTHENTICATED_SESSION_STORAGE_ERROR =
            "VRChat accepted the sign-in, but the new session couldn't be saved securely."
        const val COOKIE_LEGACY_CLEANUP_ERROR =
            "The new session is secure, but an older saved session copy couldn't be removed."
        const val LOGOUT_STORAGE_ERROR =
            "Signed out, but some saved sign-in data couldn't be removed from this device."
        const val COOKIE_RESTORE_ERROR =
            "The previous saved session couldn't be restored after sign-in failed."
    }
}

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
