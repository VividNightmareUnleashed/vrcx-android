package com.vrcx.android.data.api

import com.vrcx.android.data.api.model.AuthToken
import com.vrcx.android.data.api.model.TwoFactorAuthRequest
import com.vrcx.android.data.api.model.TwoFactorAuthResponse
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.JsonElement
import okhttp3.Credentials
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT

interface AuthApi {
    @AuthPhase
    @NoFailureCache
    @GET("auth/user")
    suspend fun loginWithBasicAuth(@Header("Authorization") authorization: String): JsonElement

    @NoFailureCache
    @GET("auth/user")
    suspend fun getCurrentUser(): JsonElement

    @AuthPhase
    @POST("auth/twofactorauth/totp/verify")
    suspend fun verifyTotp(@Body body: TwoFactorAuthRequest): TwoFactorAuthResponse

    @AuthPhase
    @POST("auth/twofactorauth/otp/verify")
    suspend fun verifyOtp(@Body body: TwoFactorAuthRequest): TwoFactorAuthResponse

    @AuthPhase
    @POST("auth/twofactorauth/emailotp/verify")
    suspend fun verifyEmailOtp(@Body body: TwoFactorAuthRequest): TwoFactorAuthResponse

    @NoFailureCache
    @GET("auth")
    suspend fun getAuthToken(): AuthToken

    /**
     * Invalidates the current session server-side. Local cookies become unusable.
     * See https://vrchat.community/reference/logout — `PUT /api/1/logout`.
     */
    @PUT("logout")
    suspend fun logout()
}

internal fun basicAuthorization(username: String, password: String): String = Credentials.basic(
    encodeURIComponent(username),
    encodeURIComponent(password),
    StandardCharsets.UTF_8,
)

private fun encodeURIComponent(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    .replace("+", "%20")
    .replace("%21", "!")
    .replace("%27", "'")
    .replace("%28", "(")
    .replace("%29", ")")
    .replace("%7E", "~")
