package com.vrcx.android.data.websocket

import kotlinx.serialization.json.JsonElement

sealed class PipelineEvent {
    abstract val content: JsonElement?

    // Friend events
    data class FriendOnline(override val content: JsonElement?) : PipelineEvent()
    data class FriendOffline(override val content: JsonElement?) : PipelineEvent()
    data class FriendActive(override val content: JsonElement?) : PipelineEvent()
    data class FriendUpdate(override val content: JsonElement?) : PipelineEvent()
    data class FriendLocation(override val content: JsonElement?) : PipelineEvent()
    data class FriendAdd(override val content: JsonElement?) : PipelineEvent()
    data class FriendDelete(override val content: JsonElement?) : PipelineEvent()

    // User events
    data class UserUpdate(override val content: JsonElement?) : PipelineEvent()
    data class UserLocation(override val content: JsonElement?) : PipelineEvent()

    // Notification events
    data class Notification(override val content: JsonElement?) : PipelineEvent()
    data class NotificationV2(override val content: JsonElement?) : PipelineEvent()
    data class NotificationV2Delete(override val content: JsonElement?) : PipelineEvent()
    data class NotificationV2Update(override val content: JsonElement?) : PipelineEvent()
    data class SeeNotification(override val content: JsonElement?) : PipelineEvent()
    data class HideNotification(override val content: JsonElement?) : PipelineEvent()
    data class ResponseNotification(override val content: JsonElement?) : PipelineEvent()

    // Group events
    data class GroupJoined(override val content: JsonElement?) : PipelineEvent()
    data class GroupLeft(override val content: JsonElement?) : PipelineEvent()
    data class GroupRoleUpdated(override val content: JsonElement?) : PipelineEvent()
    data class GroupMemberUpdated(override val content: JsonElement?) : PipelineEvent()

    // Instance events
    data class InstanceQueueJoined(override val content: JsonElement?) : PipelineEvent()
    data class InstanceQueuePosition(override val content: JsonElement?) : PipelineEvent()
    data class InstanceQueueReady(override val content: JsonElement?) : PipelineEvent()
    data class InstanceQueueLeft(override val content: JsonElement?) : PipelineEvent()
    data class InstanceClosed(override val content: JsonElement?) : PipelineEvent()

    // Other events
    data class ContentRefresh(override val content: JsonElement?) : PipelineEvent()
    data class Unknown(val type: String, override val content: JsonElement?) : PipelineEvent()
}
