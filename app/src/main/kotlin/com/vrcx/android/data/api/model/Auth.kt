package com.vrcx.android.data.api.model

import kotlinx.serialization.Serializable

@Serializable
data class TwoFactorAuthResponse(
    val verified: Boolean = false,
)

@Serializable
data class TwoFactorAuthRequest(
    val code: String,
)

@Serializable
data class AuthToken(
    val token: String = "",
)
