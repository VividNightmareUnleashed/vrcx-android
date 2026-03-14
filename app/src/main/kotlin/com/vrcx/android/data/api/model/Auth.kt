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

/**
 * When 2FA is required, the API returns a partial user object with
 * requiresTwoFactorAuth field listing the available methods.
 */
@Serializable
data class AuthResponse(
    val requiresTwoFactorAuth: List<String> = emptyList(),
    // If 2FA not required, full CurrentUser fields are returned
    val id: String? = null,
    val displayName: String? = null,
)
