package com.vrcx.android.data.api.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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
data class NotificationAction(
    val type: String = "",
    val text: String = "",
    val data: String = "",
    val icon: String = "",
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
    val responses: List<NotificationAction> = emptyList(),
    val responseData: JsonElement? = null,
    val expiresAt: String = "",
    val expiryAfterSeen: Int? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class InviteRequest(
    val instanceId: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val messageSlot: Int? = null,
)

@Serializable
data class InviteResponseRequest(
    val responseSlot: Int,
)

@Serializable
data class NotificationResponse(
    val responseType: String,
    val responseData: String = "",
)
