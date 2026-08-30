package com.vrcx.android.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vrcx.android.data.preferences.ThemeMode
import com.vrcx.android.data.preferences.VrcxPreferences
import com.vrcx.android.ui.common.MainDispatcherRule
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class VrcxAppTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        shadowOf(application).clearStartedServices()
    }

    @Test
    fun `startup decisions stay unresolved until DataStore answers`() {
        // Guessing paints the whole shell in the wrong theme for the length of a
        // disk read, then flips it. Null lets the shell follow the system, which
        // is what the window background behind it is already doing. The service
        // likewise cannot guess "enabled" without briefly overriding an opt-out.
        val preferences = mock<VrcxPreferences>()
        whenever(preferences.themeMode).thenReturn(flowOf(ThemeMode.LIGHT))
        whenever(preferences.dynamicColors).thenReturn(emptyFlow())
        whenever(preferences.wallpaperUri).thenReturn(emptyFlow())
        whenever(preferences.wallpaperScaleMode).thenReturn(emptyFlow())
        whenever(preferences.backgroundServiceEnabled).thenReturn(emptyFlow())

        val viewModel = VrcxAppViewModel(preferences)

        assertNull(viewModel.themeMode.value)
        assertNull(viewModel.backgroundServiceEnabled.value)
    }

    @Test
    fun `no session means no declaration at all, not a stop`() {
        // authState starts NotLoggedIn, so this is what every cold start and
        // every configuration change runs first. Stopping here killed a socket
        // the boot reconnect had already brought up.
        declareSocketState(application, isLoggedIn = false, backgroundServiceEnabled = true)
        declareSocketState(application, isLoggedIn = false, backgroundServiceEnabled = false)

        assertNull(shadowOf(application).nextStartedService)
        assertNull("the service owns its own teardown", shadowOf(application).nextStoppedService)
    }

    @Test
    fun `a session waits for the background preference before declaring a service mode`() {
        declareSocketState(application, isLoggedIn = true, backgroundServiceEnabled = null)

        assertNull(shadowOf(application).nextStartedService)
        assertNull(shadowOf(application).nextStoppedService)
    }

    @Test
    fun `a session declares the mode the preference asks for and stops nothing`() {
        declareSocketState(application, isLoggedIn = true, backgroundServiceEnabled = true)

        assertEquals(ACTION_START, shadowOf(application).nextStartedService?.action)
        assertNull(shadowOf(application).nextStoppedService)

        shadowOf(application).clearStartedServices()
        declareSocketState(application, isLoggedIn = true, backgroundServiceEnabled = false)

        assertEquals(ACTION_START_NON_FOREGROUND, shadowOf(application).nextStartedService?.action)
        assertNull(shadowOf(application).nextStoppedService)
    }

    @Test
    fun `re-declaring the same state issues a start and never a stop`() {
        // Rotation, dark mode, font size and multi-window all re-run the effect.
        repeat(3) {
            declareSocketState(application, isLoggedIn = true, backgroundServiceEnabled = true)
            assertNotNull(shadowOf(application).nextStartedService)
        }
        assertNull(shadowOf(application).nextStoppedService)
    }

    private companion object {
        const val ACTION_START = "com.vrcx.android.START_WEBSOCKET"
        const val ACTION_START_NON_FOREGROUND = "com.vrcx.android.START_WEBSOCKET_NON_FOREGROUND"
    }
}
