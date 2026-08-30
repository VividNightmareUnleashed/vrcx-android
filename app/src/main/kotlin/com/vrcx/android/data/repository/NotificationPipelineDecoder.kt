package com.vrcx.android.data.repository

import com.vrcx.android.data.api.model.NotificationV2
import com.vrcx.android.data.api.model.VrcNotification
import com.vrcx.android.data.websocket.PipelineEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

internal sealed interface DecodedNotificationEvent {
    data class AddV1(val notification: VrcNotification) : DecodedNotificationEvent

    data class AddV2(val notification: NotificationV2) : DecodedNotificationEvent

    data class Remove(val notificationIds: List<String>) : DecodedNotificationEvent

    data class MarkSeen(val notificationId: String) : DecodedNotificationEvent

    data class UpdateV2(val notificationId: String, val updates: JsonObject) : DecodedNotificationEvent

    data class InstanceClosed(val location: String) : DecodedNotificationEvent

    data object Clear : DecodedNotificationEvent

    data object Ignored : DecodedNotificationEvent
}

/** Converts untrusted pipeline shapes into typed inbox operations. */
internal class NotificationPipelineDecoder(private val json: Json) {
    fun decode(event: PipelineEvent): DecodedNotificationEvent = when (event) {
        is PipelineEvent.Notification -> decodeAddedV1(event.content)
        is PipelineEvent.NotificationV2 -> decodeAddedV2(event.content)
        is PipelineEvent.NotificationV2Delete -> decodeV2Delete(event.content)
        is PipelineEvent.NotificationV2Update -> decodeV2Update(event.content)
        is PipelineEvent.SeeNotification -> decodeId(event.content, markSeen = true)
        is PipelineEvent.HideNotification -> decodeId(event.content, markSeen = false)
        is PipelineEvent.ResponseNotification -> decodeResponse(event.content)
        is PipelineEvent.InstanceClosed -> decodeInstanceClosed(event.content)
        PipelineEvent.ClearNotification -> DecodedNotificationEvent.Clear
        else -> DecodedNotificationEvent.Ignored
    }

    fun applyPatch(existing: NotificationV2, update: DecodedNotificationEvent.UpdateV2): NotificationV2? {
        val existingObject = json.encodeToJsonElement(NotificationV2.serializer(), existing).jsonObject
        val updatedPayload = buildJsonObject {
            existingObject.forEach { (key, value) -> put(key, value) }
            update.updates.forEach { (key, value) -> put(key, value) }
            put("id", JsonPrimitive(update.notificationId))
        }
        return json.decodeNotificationV2(updatedPayload)
    }

    private fun decodeAddedV1(content: JsonElement?): DecodedNotificationEvent {
        val notification = json.decodeNotificationV1(content)
        return if (notification == null || notification.id.isBlank()) {
            DecodedNotificationEvent.Ignored
        } else {
            DecodedNotificationEvent.AddV1(notification)
        }
    }

    private fun decodeAddedV2(content: JsonElement?): DecodedNotificationEvent {
        val notification = json.decodeNotificationV2(content)
        return if (notification == null || notification.id.isBlank()) {
            DecodedNotificationEvent.Ignored
        } else {
            DecodedNotificationEvent.AddV2(notification)
        }
    }

    private fun decodeV2Delete(content: JsonElement?): DecodedNotificationEvent {
        val ids = (content as? JsonObject)
            ?.get("ids")
            ?.let { it as? JsonArray }
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            .orEmpty()
        return DecodedNotificationEvent.Remove(ids)
    }

    private fun decodeV2Update(content: JsonElement?): DecodedNotificationEvent {
        val contentObject = content as? JsonObject ?: return DecodedNotificationEvent.Ignored
        val notificationId = (contentObject["id"] as? JsonPrimitive)?.content
            ?: return DecodedNotificationEvent.Ignored
        val updates = contentObject["updates"] as? JsonObject ?: return DecodedNotificationEvent.Ignored
        return DecodedNotificationEvent.UpdateV2(notificationId, updates)
    }

    private fun decodeId(content: JsonElement?, markSeen: Boolean): DecodedNotificationEvent {
        val notificationId = (content as? JsonPrimitive)?.content ?: return DecodedNotificationEvent.Ignored
        return if (markSeen) {
            DecodedNotificationEvent.MarkSeen(notificationId)
        } else {
            DecodedNotificationEvent.Remove(listOf(notificationId))
        }
    }

    private fun decodeResponse(content: JsonElement?): DecodedNotificationEvent {
        val notificationId = ((content as? JsonObject)?.get("notificationId") as? JsonPrimitive)?.content
            ?: return DecodedNotificationEvent.Ignored
        return DecodedNotificationEvent.Remove(listOf(notificationId))
    }

    private fun decodeInstanceClosed(content: JsonElement?): DecodedNotificationEvent {
        val location = ((content as? JsonObject)?.get("instanceLocation") as? JsonPrimitive)?.content.orEmpty()
        return DecodedNotificationEvent.InstanceClosed(location)
    }
}

private fun Json.decodeNotificationV1(content: JsonElement?): VrcNotification? {
    val contentObject = content as? JsonObject ?: return null
    return runCatching {
        decodeFromJsonElement(VrcNotification.serializer(), contentObject)
    }.getOrNull()
}

private fun Json.decodeNotificationV2(content: JsonElement?): NotificationV2? {
    val contentObject = content as? JsonObject ?: return null
    return runCatching {
        decodeFromJsonElement(NotificationV2.serializer(), contentObject)
    }.getOrNull()
}
