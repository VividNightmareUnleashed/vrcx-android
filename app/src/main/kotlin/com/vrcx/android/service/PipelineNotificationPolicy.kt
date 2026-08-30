package com.vrcx.android.service

import com.vrcx.android.data.model.FriendTransition
import com.vrcx.android.data.repository.NotificationKind
import com.vrcx.android.data.repository.NotificationSource
import com.vrcx.android.data.repository.UnifiedNotification

internal fun notifyFriendTransition(helper: NotificationHelper, transition: FriendTransition) {
    when (transition) {
        is FriendTransition.CameOnline ->
            helper.notifyFriendOnline(transition.displayName, transition.userId)

        is FriendTransition.CameOffline ->
            helper.notifyFriendOffline(transition.displayName, transition.userId)

        is FriendTransition.ChangedLocation ->
            helper.notifyFriendLocation(transition.displayName, transition.worldName, transition.userId)

        is FriendTransition.ChangedStatus ->
            helper.notifyFriendStatusChange(transition.displayName, transition.status, transition.userId)
    }
}

internal fun dispatchNotification(
    helper: NotificationHelper,
    notification: UnifiedNotification,
    notifyInvite: Boolean,
    notifyFriendRequest: Boolean,
    notifyGeneral: Boolean,
) {
    val sender = notification.senderUsername.ifBlank { "Someone" }
    when (notification.kind) {
        NotificationKind.FRIEND_REQUEST ->
            if (notifyFriendRequest) helper.notifyFriendRequest(sender, notification.senderUserId)

        NotificationKind.INVITE, NotificationKind.REQUEST_INVITE ->
            if (notifyInvite) helper.notifyInvite(sender, notification.senderUserId)

        NotificationKind.OTHER -> when (notification.source) {
            NotificationSource.V1 -> Unit

            NotificationSource.V2 -> if (notifyGeneral) {
                helper.notifyGeneral(
                    title = boundRemoteText(notification.title.ifBlank { sender }),
                    text = boundRemoteText(notification.message.ifBlank { "New notification" }),
                )
            }

            NotificationSource.LOCAL -> helper.notifyGeneral(
                title = boundRemoteText(notification.title),
                text = boundRemoteText(notification.message),
            )
        }
    }
}

private const val MAX_REMOTE_NOTIFICATION_CHARS = 120

// Remote payloads control this text, so bound what can appear on the lock screen.
internal fun boundRemoteText(value: String, max: Int = MAX_REMOTE_NOTIFICATION_CHARS): String =
    if (value.length <= max) value else value.take(max - 1).trimEnd() + "…"
