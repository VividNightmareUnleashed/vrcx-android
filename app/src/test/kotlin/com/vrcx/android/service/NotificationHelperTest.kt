package com.vrcx.android.service

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.vrcx.android.MainActivity
import com.vrcx.android.ui.navigation.DeepLinkSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class NotificationHelperTest {

    private lateinit var application: Application
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        notificationManager = application.getSystemService(NotificationManager::class.java)
        notificationManager.cancelAll()
    }

    @Test
    fun `a friend notification opens that friend's screen`() {
        NotificationHelper(application).notifyFriendOnline("Alice", "usr_alice")

        val intent = postedContentIntent()
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(Uri.parse("vrcx://user/usr_alice"), intent.data)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertTrue(
            "the tap must reuse the running shell rather than stack a second one",
            intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0,
        )
    }

    @Test
    fun `an invite opens the sender's screen`() {
        NotificationHelper(application).notifyInvite("Bob", "usr_bob")

        assertEquals(Uri.parse("vrcx://user/usr_bob"), postedContentIntent().data)
    }

    @Test
    fun `a target id is percent-encoded into the link`() {
        NotificationHelper(application)
            .notifyGeneral("Group", "posted", NotificationTarget(DeepLinkSection.GROUP, "grp_a/b"))

        assertEquals(Uri.parse("vrcx://group/grp_a%2Fb"), postedContentIntent().data)
    }

    @Test
    fun `notifications with no target still just open the app`() {
        // Reserved prompts have nowhere to point; they must keep working.
        NotificationHelper(application).notifyBootReconnectRequired()

        val intent = postedContentIntent()
        assertNull(intent.data)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
    }

    @Test
    fun `rolling notification ids start above every fixed id in the app`() {
        // 1 is the foreground service, 98 and 99 the reconnect prompts, and 1001
        // is the id WorkManager owns while BootReconnectWorker holds the
        // foreground — a rolling id landing on it replaces someone else's.
        val reserved = setOf(
            NotificationHelper.SERVICE_NOTIFICATION_ID,
            98,
            99,
            NotificationHelper.BOOT_WORKER_NOTIFICATION_ID,
        )

        NotificationHelper(application).notifyFriendOnline("Alice", "usr_alice")

        val id = notificationManager.activeNotifications.single().id
        assertFalse("$id collides with a fixed id", id in reserved)
        assertTrue("rolling ids must start above $reserved, got $id", id > reserved.max())
    }

    @Test
    fun `the shared owner registers channels and builds both ongoing notifications`() {
        val helper = NotificationHelper(application)

        val expectedChannels = setOf(
            NotificationHelper.CHANNEL_SERVICE,
            NotificationHelper.CHANNEL_FRIEND_ONLINE,
            NotificationHelper.CHANNEL_FRIEND_OFFLINE,
            NotificationHelper.CHANNEL_INVITES,
            NotificationHelper.CHANNEL_FRIEND_REQUEST,
            NotificationHelper.CHANNEL_GENERAL,
        )
        assertEquals(
            expectedChannels,
            notificationManager.notificationChannels.map {
                it.id
            }.toSet().intersect(expectedChannels),
        )

        val service = helper.createWebSocketServiceNotification()
        val worker = helper.createBootWorkerNotification()
        assertEquals(NotificationHelper.CHANNEL_SERVICE, service.channelId)
        assertEquals(NotificationHelper.CHANNEL_SERVICE, worker.channelId)
        assertTrue(service.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(worker.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals("Connected to VRChat", service.extras.getCharSequence("android.text"))
        assertEquals(
            "Restoring background connection",
            worker.extras.getCharSequence("android.text"),
        )
    }

    private fun postedContentIntent(): Intent {
        val posted = notificationManager.activeNotifications.single().notification
        return shadowOf(posted.contentIntent).savedIntent
    }
}
