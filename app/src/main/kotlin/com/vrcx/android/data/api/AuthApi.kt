package com.vrcx.android.data.api

import com.vrcx.android.data.api.model.AuthToken
import com.vrcx.android.data.api.model.CurrentUser
import com.vrcx.android.data.api.model.TwoFactorAuthRequest
import com.vrcx.android.data.api.model.TwoFactorAuthResponse
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT

interface AuthApi {
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

    @GET("auth")
    suspend fun getAuthToken(): AuthToken

    /**
     * Invalidates the current session server-side. Local cookies become unusable.
     * See https://vrchat.community/reference/logout — `PUT /api/1/logout`.
     */
    @PUT("logout")
    suspend fun logout()
}
