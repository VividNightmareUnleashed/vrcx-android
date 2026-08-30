package com.vrcx.android.data.repository

import com.vrcx.android.data.api.AuthApi
import com.vrcx.android.data.api.basicAuthorization
import com.vrcx.android.data.api.model.CurrentUser
import com.vrcx.android.data.api.model.TwoFactorAuthRequest
import com.vrcx.android.data.api.model.TwoFactorAuthResponse
import com.vrcx.android.data.util.runCatchingCancellable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException

internal sealed interface RemoteLoginResult {
    data class Authenticated(val user: CurrentUser) : RemoteLoginResult
    data class TwoFactorRequired(val methods: List<String>) : RemoteLoginResult
}

/** Outcome of asking VRChat whether the stored session is still usable. */
internal sealed interface SessionCheck {
    data class Active(val user: CurrentUser) : SessionCheck
    data class TwoFactorRequired(val methods: List<String>) : SessionCheck

    /** The server rejected the credentials, so the session is gone for good. */
    data class Rejected(val message: String) : SessionCheck

    /** The server never answered conclusively; the session may still be valid. */
    data class Inconclusive(val message: String) : SessionCheck
}

/** The only owner of Retrofit authentication calls and their external response shapes. */
internal class AuthRemoteGateway(private val authApi: AuthApi, private val json: Json) {
    suspend fun login(username: String, password: String): RemoteLoginResult {
        val response = authApi.loginWithBasicAuth(basicAuthorization(username, password))
        return decodeLoginResponse(response)
    }

    suspend fun verifyTotp(code: String, recoveryCode: Boolean): TwoFactorAuthResponse = if (recoveryCode) {
        authApi.verifyOtp(TwoFactorAuthRequest(code))
    } else {
        authApi.verifyTotp(TwoFactorAuthRequest(code))
    }

    suspend fun verifyEmailOtp(code: String): TwoFactorAuthResponse = authApi.verifyEmailOtp(TwoFactorAuthRequest(code))

    suspend fun checkSession(): SessionCheck {
        val response = runCatchingCancellable {
            decodeSessionResponse(authApi.getCurrentUser())
        }
        return response.getOrElse(::classifySessionFailure)
    }

    suspend fun fetchAuthToken(): String? = runCatchingCancellable { authApi.getAuthToken().token }.getOrNull()

    suspend fun logout(): Result<Unit> = runCatchingCancellable { authApi.logout() }

    private fun decodeLoginResponse(response: JsonElement): RemoteLoginResult {
        val jsonObject = response.jsonObject
        return if (jsonObject.containsKey(REQUIRES_TWO_FACTOR_AUTH)) {
            RemoteLoginResult.TwoFactorRequired(twoFactorMethods(response))
        } else {
            RemoteLoginResult.Authenticated(
                json.decodeFromJsonElement(CurrentUser.serializer(), response),
            )
        }
    }

    private fun decodeSessionResponse(response: JsonElement): SessionCheck {
        val jsonObject = response.jsonObject
        return if (jsonObject.containsKey(REQUIRES_TWO_FACTOR_AUTH)) {
            SessionCheck.TwoFactorRequired(twoFactorMethods(response))
        } else {
            SessionCheck.Active(json.decodeFromJsonElement(CurrentUser.serializer(), response))
        }
    }

    private fun classifySessionFailure(failure: Throwable): SessionCheck = if (isCredentialRejection(failure)) {
        SessionCheck.Rejected(failure.message ?: "Session expired")
    } else {
        SessionCheck.Inconclusive(failure.message ?: UNREACHABLE_MESSAGE)
    }

    private fun twoFactorMethods(response: JsonElement): List<String> =
        response.jsonObject[REQUIRES_TWO_FACTOR_AUTH]?.jsonArray
            ?.map { method -> method.jsonPrimitive.content }
            .orEmpty()

    private companion object {
        const val REQUIRES_TWO_FACTOR_AUTH = "requiresTwoFactorAuth"
    }
}

internal fun isCredentialRejection(failure: Throwable): Boolean = failure is HttpException &&
    (failure.code() == HTTP_UNAUTHORIZED || failure.code() == HTTP_FORBIDDEN)

internal const val UNREACHABLE_MESSAGE = "Couldn't reach VRChat"
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
