package com.vrcx.android.data.repository

enum class NotificationCategory {
    FRIEND,
    GROUP,
    OTHER,
}

enum class NotificationCategoryFilter(val label: String) {
    ALL("All"),
    FRIEND("Friends"),
    GROUP("Groups"),
    OTHER("Other"),
}

data class NotificationCategoryCount(
    val filter: NotificationCategoryFilter,
    val count: Int,
)

private val FRIEND_NOTIFICATION_TYPES = setOf(
    "friendRequest",
    "ignoredFriendRequest",
    "invite",
    "requestInvite",
    "inviteResponse",
    "requestInviteResponse",
    "boop",
)

private val GROUP_NOTIFICATION_PREFIXES = listOf(
    "group.",
    "moderation.",
)

private val GROUP_NOTIFICATION_EXACT_TYPES = setOf(
    "groupChange",
    "event.announcement",
)

private val NOTIFICATION_TYPE_LABELS = mapOf(
    "boop" to "Boop",
    "event.announcement" to "Event Announcement",
    "friendRequest" to "Friend Request",
    "groupChange" to "Group Change",
    "ignoredFriendRequest" to "Ignored Friend Request",
    "instance.closed" to "Instance Closed",
    "invite" to "Invite",
    "inviteResponse" to "Invite Response",
    "requestInvite" to "Request Invite",
    "requestInviteResponse" to "Request Invite Response",
)

fun notificationCategoryOf(type: String): NotificationCategory {
    if (type.isBlank()) {
        return NotificationCategory.OTHER
    }
    if (type in FRIEND_NOTIFICATION_TYPES) {
        return NotificationCategory.FRIEND
    }
    if (type in GROUP_NOTIFICATION_EXACT_TYPES || GROUP_NOTIFICATION_PREFIXES.any(type::startsWith)) {
        return NotificationCategory.GROUP
    }
    return NotificationCategory.OTHER
}

fun notificationTypeLabel(type: String): String {
    return NOTIFICATION_TYPE_LABELS[type] ?: type
        .replace('.', ' ')
        .replace('_', ' ')
        .split(' ')
        .filter(String::isNotBlank)
        .joinToString(" ") { word ->
            word.replaceFirstChar { first ->
                if (first.isLowerCase()) {
                    first.titlecase()
                } else {
                    first.toString()
                }
            }
        }
}

fun UnifiedNotification.matchesCategory(filter: NotificationCategoryFilter): Boolean {
    return when (filter) {
        NotificationCategoryFilter.ALL -> true
        NotificationCategoryFilter.FRIEND -> notificationCategoryOf(type) == NotificationCategory.FRIEND
        NotificationCategoryFilter.GROUP -> notificationCategoryOf(type) == NotificationCategory.GROUP
        NotificationCategoryFilter.OTHER -> notificationCategoryOf(type) == NotificationCategory.OTHER
    }
}
