package com.vrcx.android.service

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.vrcx.android.data.model.FriendTransition
import com.vrcx.android.data.repository.AccountChangedException
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.data.websocket.PipelineEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class WebSocketForegroundServiceTest {

    private lateinit var application: Application
    private val context: Context get() = application
    private val notificationManager: NotificationManager
        get() = application.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(SERVICE_STATE_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        shadowOf(application).clearStartedServices()
    }

    @Test
    fun `the recorded mode round-trips and anything unrecognised reads as NONE`() {
        assertEquals(ServiceMode.NONE, WebSocketForegroundService.serviceMode(context))
        for (mode in ServiceMode.values()) {
            WebSocketForegroundService.setServiceMode(context, mode)
            assertEquals(mode, WebSocketForegroundService.serviceMode(context))
        }

        context.getSharedPreferences(SERVICE_STATE_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putInt("requested_mode", 99)
            .commit()
        assertEquals(ServiceMode.NONE, WebSocketForegroundService.serviceMode(context))
    }

    @Test
    fun `the Android 15 recovery only starts the service after an actual timeout`() {
        for (mode in listOf(ServiceMode.NONE, ServiceMode.FOREGROUND, ServiceMode.NON_FOREGROUND)) {
            WebSocketForegroundService.setServiceMode(context, mode)
            assertFalse(
                "mode=$mode must not trigger the timeout recovery",
                WebSocketForegroundService.restartAfterTimeoutIfNeeded(context),
            )
            assertNull(shadowOf(application).nextStartedService)
        }

        WebSocketForegroundService.setServiceMode(context, ServiceMode.TIMED_OUT)
        assertTrue(WebSocketForegroundService.restartAfterTimeoutIfNeeded(context))
        val started = shadowOf(application).nextStartedService
        assertNotNull(started)
        assertEquals(ACTION_START, started!!.action)
    }

    @Test
    fun `an explicit stop clears a pending timeout recovery so it cannot start a service later`() {
        // A service that stopped itself on timeout never reaches onDestroy, and
        // is not around to notice the session ending either, so stop() is the
        // only thing standing between a stale recovery and an ongoing
        // notification the user never asked for.
        WebSocketForegroundService.setServiceMode(context, ServiceMode.TIMED_OUT)

        WebSocketForegroundService.stop(context)

        assertEquals(ServiceMode.NONE, WebSocketForegroundService.serviceMode(context))
        shadowOf(application).clearStartedServices()
        assertFalse(WebSocketForegroundService.restartAfterTimeoutIfNeeded(context))
        assertNull(shadowOf(application).nextStartedService)
    }

    @Test
    fun `an inconclusive resume keeps the service alive and retries`() {
        // AuthRepository deliberately keeps the cookies when it could not reach
        // VRChat, so a stored session that did not resume may still be good.
        for (state in listOf(AuthState.NotLoggedIn, AuthState.LoggingIn, AuthState.Error("Unreachable"))) {
            assertEquals(
                "state=$state with a stored cookie must retry",
                SessionStartup.RETRY,
                sessionStartupDecision(authState = state, hasResumableSession = true),
            )
        }
    }

    @Test
    fun `a finished session or a two-factor challenge stops the service`() {
        assertEquals(
            SessionStartup.STOP,
            sessionStartupDecision(authState = AuthState.Error("Session expired"), hasResumableSession = false),
        )
        // Waiting would only flap the login screen the user is typing into.
        assertEquals(
            SessionStartup.STOP,
            sessionStartupDecision(
                authState = AuthState.RequiresTwoFactor(listOf("totp")),
                hasResumableSession = true,
            ),
        )
    }

    @Test
    fun `the resume backoff grows and settles at the ceiling`() {
        assertEquals(10_000L, sessionRetryDelayMs(1))
        assertEquals(20_000L, sessionRetryDelayMs(2))
        assertEquals(40_000L, sessionRetryDelayMs(3))
        assertEquals(300_000L, sessionRetryDelayMs(20))
        assertEquals(300_000L, sessionRetryDelayMs(5_000))
    }

    @Test
    fun `pipeline-supplied notification text is capped`() {
        assertEquals("short", boundRemoteText("short", max = 10))
        val capped = boundRemoteText("x".repeat(500), max = 10)
        assertEquals(10, capped.length)
        assertEquals(120, boundRemoteText("y".repeat(4_000)).length)
    }

    @Test
    fun `a handler that throws costs one capability, not the collector`() {
        var laterCapabilityRan = false

        containPipelineFailure("friends") {
            // The shape kotlinx throws on: an array where an object was assumed.
            throw IllegalArgumentException("Element is not a JsonObject")
        }
        containPipelineFailure("notifications") { laterCapabilityRan = true }

        assertTrue("a later consumer must still receive the frame", laterCapabilityRan)
    }

    @Test
    fun `an account change is absorbed but real cancellation is rethrown`() {
        // AccountChangedException is a CancellationException subclass raised on
        // purpose to abandon work for the previous account.
        containPipelineFailure("gallery") {
            throw AccountChangedException("Gallery load invalidated by account change")
        }

        var rethrown = false
        try {
            containPipelineFailure("friends") { throw CancellationException("scope cancelled") }
        } catch (_: CancellationException) {
            rethrown = true
        }
        assertTrue("genuine cancellation must reach the caller", rethrown)
    }

    @Test
    fun `a friend notification points at the friend it names`() {
        val helper = NotificationHelper(application)
        val transitions = listOf(
            FriendTransition.CameOnline("usr_a", "Alice"),
            FriendTransition.CameOffline("usr_b", "Bob"),
            FriendTransition.ChangedLocation("usr_c", "Carol", "The Great Pug"),
            FriendTransition.ChangedStatus("usr_d", "Dave", "join me"),
        )

        for (transition in transitions) {
            notificationManager.cancelAll()
            notifyFriendTransition(helper, transition)
            assertEquals(
                "$transition must open its own screen",
                Uri.parse("vrcx://user/${transition.userId}"),
                postedContentIntent().data,
            )
        }
    }

    @Test
    fun `an invite notification points at the sender`() {
        val helper = NotificationHelper(application)
        notificationManager.cancelAll()

        dispatchNotification(
            helper = helper,
            event = PipelineEvent.NotificationV2(
                buildJsonObject {
                    put("type", JsonPrimitive("invite"))
                    put("senderUsername", JsonPrimitive("Bob"))
                    put("senderUserId", JsonPrimitive("usr_bob"))
                }
            ),
            notifyInvite = true,
            notifyFriendRequest = true,
            notifyGeneral = true,
        )

        assertEquals(Uri.parse("vrcx://user/usr_bob"), postedContentIntent().data)
    }

    @Test
    fun `the notification preferences silence their own kinds`() {
        val helper = NotificationHelper(application)
        notificationManager.cancelAll()

        for (type in listOf("invite", "requestInvite", "friendRequest")) {
            dispatchNotification(
                helper = helper,
                event = PipelineEvent.Notification(
                    buildJsonObject { put("type", JsonPrimitive(type)) }
                ),
                notifyInvite = false,
                notifyFriendRequest = false,
                notifyGeneral = false,
            )
        }

        assertEquals(0, notificationManager.activeNotifications.size)
    }

    @Test
    fun `a notification type the app cannot classify is gated and capped`() {
        val helper = NotificationHelper(application)
        val unknownType = PipelineEvent.NotificationV2(
            buildJsonObject {
                put("type", JsonPrimitive("somethingNewVRChatShipped"))
                put("title", JsonPrimitive("T".repeat(500)))
                put("message", JsonPrimitive("M".repeat(500)))
            }
        )
        notificationManager.cancelAll()

        // The text is whoever sent it's to choose, so the user gets a switch for
        // the whole category rather than only for the kinds the app models.
        dispatchNotification(helper, unknownType, notifyInvite = true, notifyFriendRequest = true, notifyGeneral = false)
        assertEquals(0, notificationManager.activeNotifications.size)

        dispatchNotification(helper, unknownType, notifyInvite = false, notifyFriendRequest = false, notifyGeneral = true)
        val posted = notificationManager.activeNotifications.single().notification.extras
        assertEquals(120, posted.getCharSequence("android.title")!!.length)
        assertEquals(120, posted.getCharSequence("android.text")!!.length)
    }

    @Test
    fun `the service stops itself when the session ends, and not before`() = runTest {
        val authState = MutableStateFlow<AuthState>(AuthState.LoggingIn)
        var stops = 0
        val observer = launch { stopWhenSessionEnds(authState) { stops++ } }
        runCurrent()

        // A session still coming up must not take the socket down with it.
        authState.value = AuthState.Error("Couldn't reach VRChat")
        runCurrent()
        assertEquals(0, stops)

        // Sign-out and a confirmed 401 both land here, and the ongoing
        // notification must not outlive either.
        authState.value = AuthState.NotLoggedIn
        runCurrent()
        assertEquals(1, stops)

        observer.cancel()
    }

    private fun postedContentIntent(): Intent {
        val posted = notificationManager.activeNotifications.single().notification
        return shadowOf(posted.contentIntent).savedIntent
    }

    private companion object {
        const val SERVICE_STATE_PREFERENCES = "websocket_service_state"
        const val ACTION_START = "com.vrcx.android.START_WEBSOCKET"
    }
}
