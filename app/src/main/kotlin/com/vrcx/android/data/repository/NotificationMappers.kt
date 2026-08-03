package com.vrcx.android.data.repository

import com.vrcx.android.data.api.model.NotificationAction
import com.vrcx.android.data.api.model.NotificationV2
import com.vrcx.android.data.api.model.VrcNotification
import com.vrcx.android.data.db.entity.NotificationEntity
import com.vrcx.android.data.db.entity.NotificationV2Entity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

internal fun VrcNotification.toUnified() = UnifiedNotification(
    id = id,
    type = type,
    senderUserId = senderUserId,
    senderUsername = senderUsername,
    message = message,
    title = "",
    createdAt = createdAt,
    seen = seen,
    source = NotificationSource.V1,
    responses = emptyList(),
)

internal fun NotificationV2.toUnified() = UnifiedNotification(
    id = id,
    type = type,
    senderUserId = senderUserId,
    senderUsername = senderUsername,
    message = message,
    title = title,
    createdAt = createdAt,
    seen = seen,
    source = NotificationSource.V2,
    responses = responses,
)

internal fun VrcNotification.toEntity(ownerUserId: String) = NotificationEntity(
    id = id,
    ownerUserId = ownerUserId,
    type = type,
    senderUserId = senderUserId,
    senderUsername = senderUsername,
    receiverUserId = receiverUserId,
    message = message,
    details = details?.toString().orEmpty(),
    seen = seen,
    createdAt = createdAt,
)

internal fun NotificationEntity.toModel(json: Json) = VrcNotification(
    createdAt = createdAt,
    details = details.toJsonElementOrNull(json),
    id = id,
    message = message,
    receiverUserId = receiverUserId,
    seen = seen,
    senderUserId = senderUserId,
    senderUsername = senderUsername,
    type = type,
)

internal fun NotificationV2.toEntity(ownerUserId: String, json: Json) = NotificationV2Entity(
    id = id,
    ownerUserId = ownerUserId,
    version = version,
    type = type,
    category = category,
    isSystem = isSystem,
    ignoreDND = ignoreDND,
    senderUserId = senderUserId,
    senderUsername = senderUsername,
    receiverUserId = receiverUserId,
    relatedNotificationsId = relatedNotificationsId,
    title = title,
    message = message,
    seen = seen,
    responsesJson = json.encodeToString(ListSerializer(NotificationAction.serializer()), responses),
    responseDataJson = responseData?.toString().orEmpty(),
    expiresAt = expiresAt,
    expiryAfterSeen = expiryAfterSeen,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun NotificationV2Entity.toModel(json: Json) = NotificationV2(
    id = id,
    version = version,
    type = type,
    category = category,
    isSystem = isSystem,
    ignoreDND = ignoreDND,
    senderUserId = senderUserId,
    senderUsername = senderUsername,
    receiverUserId = receiverUserId,
    relatedNotificationsId = relatedNotificationsId,
    title = title,
    message = message,
    seen = seen,
    responses = responsesJson.toNotificationActions(json),
    responseData = responseDataJson.toJsonElementOrNull(json),
    expiresAt = expiresAt,
    expiryAfterSeen = expiryAfterSeen,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun String.toJsonElementOrNull(json: Json): JsonElement? {
    if (isBlank()) return null
    return runCatching { json.parseToJsonElement(this) }.getOrNull()
}

private fun String.toNotificationActions(json: Json): List<NotificationAction> {
    if (isBlank()) return emptyList()
    return runCatching {
        json.decodeFromString(ListSerializer(NotificationAction.serializer()), this)
    }.getOrDefault(emptyList())
}
