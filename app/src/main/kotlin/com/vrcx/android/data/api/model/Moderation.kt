package com.vrcx.android.data.api.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PlayerModeration(
    @SerialName("created") val created: String = "",
    val id: String = "",
    val sourceDisplayName: String = "",
    val sourceUserId: String = "",
    val targetDisplayName: String = "",
    val targetUserId: String = "",
    val type: String = "",
)

@Serializable
data class PlayerModerationRequest(
    val moderated: String,
    val type: String,
)
