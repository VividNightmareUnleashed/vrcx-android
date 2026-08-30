package com.vrcx.android.data.repository

import com.vrcx.android.data.api.model.NotificationV2
import com.vrcx.android.data.api.model.VrcNotification
import com.vrcx.android.data.websocket.PipelineEvent
import java.time.Instant

/** Applies decoded pipeline operations under the inbox's account/revision guard. */
internal class NotificationEventCoordinator(
    private val account: AccountScope,
    private val state: NotificationInboxState,
    private val storage: NotificationStorageWorker,
    private val decoder: NotificationPipelineDecoder,
) {
    fun handle(event: PipelineEvent, token: AccountScope.Token): AccountScopedEvent<UnifiedNotification>? {
        if (token.ownerUserId.isEmpty() || !account.isCurrent(token)) return null
        val revision = state.currentRevision()
        val decoded = decoder.decode(event)
        var notificationToRender: UnifiedNotification? = null
        state.mutate(token, revision) {
            notificationToRender = applyEvent(decoded, token)
        }
        return notificationToRender?.let { notification ->
            AccountScopedEvent(origin = token, value = notification)
        }
    }

    private fun applyEvent(event: DecodedNotificationEvent, token: AccountScope.Token): UnifiedNotification? =
        when (event) {
            is DecodedNotificationEvent.AddV1 -> addV1(event.notification, token)

            is DecodedNotificationEvent.AddV2 -> addV2(event.notification, token)

            is DecodedNotificationEvent.Remove -> remove(event.notificationIds, token)

            is DecodedNotificationEvent.MarkSeen -> markSeen(event.notificationId, token)

            is DecodedNotificationEvent.UpdateV2 -> updateV2(event, token)

            is DecodedNotificationEvent.InstanceClosed -> instanceClosed(event.location)

            DecodedNotificationEvent.Clear -> {
                state.clearInboxLocked()
                storage.clear(token)
                null
            }

            DecodedNotificationEvent.Ignored -> null
        }

    private fun addV1(notification: VrcNotification, token: AccountScope.Token): UnifiedNotification {
        state.removedNotificationIds.remove(notification.id)
        state.v1.add(listOf(notification))
        state.publish()
        storage.upsert(token, notification)
        return notification.toUnified()
    }

    private fun addV2(notification: NotificationV2, token: AccountScope.Token): UnifiedNotification {
        state.removedNotificationIds.remove(notification.id)
        state.v2.add(listOf(notification))
        state.publish()
        storage.upsert(token, notification)
        return notification.toUnified()
    }

    private fun remove(notificationIds: List<String>, token: AccountScope.Token): UnifiedNotification? {
        if (notificationIds.isNotEmpty()) {
            state.removeLocked(notificationIds)
            storage.delete(token, notificationIds)
        }
        return null
    }

    private fun markSeen(notificationId: String, token: AccountScope.Token): UnifiedNotification? {
        state.v1.markSeen(notificationId)
        state.v2.markSeen(notificationId)
        state.local = state.local.map { notification ->
            if (notification.id == notificationId) notification.copy(seen = true) else notification
        }
        state.publish()
        storage.markSeen(token, notificationId)
        return null
    }

    private fun updateV2(event: DecodedNotificationEvent.UpdateV2, token: AccountScope.Token): UnifiedNotification? {
        val existing = state.v2.items.firstOrNull { it.id == event.notificationId } ?: return null
        val updated = decoder.applyPatch(existing, event) ?: return null
        state.v2.replace(event.notificationId, updated)
        state.publish()
        storage.upsert(token, updated)
        return null
    }

    private fun instanceClosed(location: String): UnifiedNotification {
        val now = Instant.now()
        val notification = UnifiedNotification(
            id = "local:instance.closed:$now",
            type = "instance.closed",
            senderUserId = "",
            senderUsername = "System",
            message = location.ifBlank { "A queued instance closed" },
            title = "Instance Closed",
            createdAt = now.toString(),
            seen = false,
            source = NotificationSource.LOCAL,
            responses = emptyList(),
        )
        state.local = (listOf(notification) + state.local).take(NotificationRepositoryLimits.MAX_LOCAL)
        state.publish()
        return notification
    }
}
