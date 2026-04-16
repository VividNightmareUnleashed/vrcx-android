package com.vrcx.android.data.repository

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

sealed class AuthState {
    data object NotLoggedIn : AuthState()
    data object LoggingIn : AuthState()
    data class RequiresTwoFactor(val methods: List<String>) : AuthState()
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
    authEventBus: AuthEventBus? = null,
) {
    private val _authState = MutableStateFlow<AuthState>(AuthState.NotLoggedIn)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private var _currentUser: CurrentUser? = null
    val currentUser: CurrentUser? get() = _currentUser

    private var _authToken: String? = null
    val authToken: String? get() = _authToken

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        // Collect unauthorized signals from ErrorInterceptor so a 401 on any
        // request immediately transitions the app back to NotLoggedIn without
        // waiting for the user to trigger an auth-aware code path.
        authEventBus?.let { bus ->
            scope.launch {
                bus.events.collect { event ->
                    when (event) {
                        AuthEvent.Unauthorized -> if (_authState.value is AuthState.LoggedIn) {
                            _currentUser = null
                            _authToken = null
                            _authState.value = AuthState.NotLoggedIn
                        }
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
            _authState.value = AuthState.Error(e.message ?: "Login failed")
        }
    }

    suspend fun verifyTotp(code: String) {
        try {
            _authState.value = AuthState.LoggingIn
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
                _authState.value = AuthState.Error("Verification failed")
            }
        } catch (e: Exception) {
            _authState.value = AuthState.Error(e.message ?: "Verification failed")
        }
    }

    suspend fun verifyEmailOtp(code: String) {
        try {
            _authState.value = AuthState.LoggingIn
            val result = authApi.verifyEmailOtp(TwoFactorAuthRequest(code))
            if (result.verified) {
                fetchCurrentUser()
            } else {
                _authState.value = AuthState.Error("Verification failed")
            }
        } catch (e: Exception) {
            _authState.value = AuthState.Error(e.message ?: "Verification failed")
        }
    }

    suspend fun fetchCurrentUser() {
        try {
            val response = authApi.getCurrentUser()
            val jsonObj = response.jsonObject
            if (jsonObj.containsKey("requiresTwoFactorAuth")) {
                val methods = jsonObj["requiresTwoFactorAuth"]?.jsonArray
                    ?.map { it.jsonPrimitive.content }
                    ?: emptyList()
                _authState.value = AuthState.RequiresTwoFactor(methods)
                return
            }
            val user = json.decodeFromJsonElement(CurrentUser.serializer(), response)
            onLoginSuccess(user)
        } catch (e: Exception) {
            _authState.value = AuthState.Error(e.message ?: "Failed to fetch user")
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

    suspend fun tryResumeSession() {
        try {
            val cookie = cookieJar.getAuthCookie()
            if (cookie != null) {
                _authState.value = AuthState.LoggingIn
                fetchCurrentUser()
            }
        } catch (_: Exception) {
            _authState.value = AuthState.NotLoggedIn
        }
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
        favoriteRepository.clearRuntimeState()
        _currentUser = null
        _authToken = null
        authInterceptor.clearBasicAuth()
        cookieJar.clearAll()
        dedup.clearCache()
        _authState.value = AuthState.NotLoggedIn
    }

    fun handleEvent(event: PipelineEvent) {
        when (event) {
            is PipelineEvent.UserUpdate -> {
                val userJson = event.content?.jsonObject?.get("user") ?: return
                try {
                    val user = json.decodeFromJsonElement(CurrentUser.serializer(), userJson)
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
        favoriteRepository.clearRuntimeState()
        _currentUser = user
        _authState.value = AuthState.LoggedIn(user)
        preferences.setLastUserId(user.id)
        fetchAuthToken()
    }
}
