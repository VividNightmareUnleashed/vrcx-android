package com.vrcx.android.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPresentationTest {

    @Test
    fun `notification categories match desktop grouping`() {
        assertEquals(NotificationCategory.FRIEND, notificationCategoryOf("friendRequest"))
        assertEquals(NotificationCategory.FRIEND, notificationCategoryOf("requestInviteResponse"))
        assertEquals(NotificationCategory.FRIEND, notificationCategoryOf("boop"))
        assertEquals(NotificationCategory.GROUP, notificationCategoryOf("group.member.join"))
        assertEquals(NotificationCategory.GROUP, notificationCategoryOf("moderation.vote"))
        assertEquals(NotificationCategory.GROUP, notificationCategoryOf("groupChange"))
        assertEquals(NotificationCategory.OTHER, notificationCategoryOf("message"))
        assertEquals(NotificationCategory.OTHER, notificationCategoryOf(""))
    }

    @Test
    fun `notification type labels are human readable`() {
        assertEquals("Friend Request", notificationTypeLabel("friendRequest"))
        assertEquals("Request Invite Response", notificationTypeLabel("requestInviteResponse"))
        assertEquals("Event Announcement", notificationTypeLabel("event.announcement"))
        assertEquals("Instance Closed", notificationTypeLabel("instance.closed"))
        assertEquals("Group Member Join", notificationTypeLabel("group.member.join"))
    }

    @Test
    fun `category filters match unified notifications`() {
        val notification = UnifiedNotification(
            id = "noty_1",
            type = "requestInvite",
            senderUserId = "usr_sender",
            senderUsername = "Sender",
            message = "",
            title = "",
            createdAt = "",
            seen = false,
            isV2 = false,
            responses = emptyList(),
        )

        assertTrue(notification.matchesCategory(NotificationCategoryFilter.ALL))
        assertTrue(notification.matchesCategory(NotificationCategoryFilter.FRIEND))
        assertFalse(notification.matchesCategory(NotificationCategoryFilter.GROUP))
        assertFalse(notification.matchesCategory(NotificationCategoryFilter.OTHER))
    }
}
