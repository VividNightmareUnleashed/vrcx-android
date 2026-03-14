package com.vrcx.android.data.api.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class VrcNotification(
    @SerialName("created_at") val createdAt: String = "",
    val details: JsonElement? = null,
    val id: String = "",
    val message: String = "",
    val receiverUserId: String = "",
    val seen: Boolean = false,
    val senderUserId: String = "",
    val senderUsername: String = "",
    val type: String = "",
)

@Serializable
data class NotificationV2(
    val id: String = "",
    val version: Int = 0,
    val type: String = "",
    val category: String = "",
    val isSystem: Boolean = false,
    val ignoreDND: Boolean = false,
    val senderUserId: String = "",
    val senderUsername: String = "",
    val receiverUserId: String = "",
    val relatedNotificationsId: String = "",
    val title: String = "",
    val message: String = "",
    val seen: Boolean = false,
    val responses: List<String> = emptyList(),
    val responseData: JsonElement? = null,
    val expiresAt: String = "",
    val expiryAfterSeen: Int? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class InviteRequest(
    val instanceId: String,
    val messageSlot: Int = 0,
)

@Serializable
data class NotificationResponse(
    val responseType: String,
    val responseData: String = "",
)
