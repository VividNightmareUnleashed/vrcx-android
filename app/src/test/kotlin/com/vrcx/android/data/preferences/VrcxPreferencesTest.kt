package com.vrcx.android.data.preferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VrcxPreferencesTest {

    @Test
    fun `an unreadable notification policy fails closed`() = runTest {
        val unreadable = flow<NotificationPolicy> { throw IOException("unreadable") }

        assertEquals(
            NotificationPolicy.DISABLED,
            unreadable.recoverIOExceptionWith(NotificationPolicy.DISABLED).first(),
        )
    }

    @Test
    fun `an unreadable background-service preference fails closed`() = runTest {
        val unreadable = flow<Boolean> { throw IOException("unreadable") }

        assertEquals(false, unreadable.recoverIOExceptionWith(false).first())
    }

    /**
     * One method rather than several: the DataStore behind `Context.dataStore` is
     * a process-wide singleton, so separate test methods would share one file and
     * depend on the order they happened to run in.
     */
    @Test
    fun `settings round-trip through storage, and sign-out keeps the ones that outlive a session`() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        val preferences = VrcxPreferences(context)

        // Nothing written yet, so every setting reads as its declared default.
        assertEquals(PreferenceDefaults.THEME_MODE, preferences.themeMode.first())
        assertEquals(PreferenceDefaults.WALLPAPER_SCALE_MODE, preferences.wallpaperScaleMode.first())
        assertEquals(PreferenceDefaults.MAX_FEED_SIZE, preferences.maxFeedSize.first())
        assertEquals(PreferenceDefaults.AUTO_LOGIN, preferences.autoLogin.first())
        assertEquals(PreferenceDefaults.NOTIFY_GENERAL, preferences.notifyGeneral.first())

        preferences.setNotifyGeneral(false)
        assertEquals(false, preferences.notifyGeneral.first())

        preferences.setThemeMode(ThemeMode.LIGHT)
        preferences.setWallpaperScaleMode(WallpaperScaleMode.FILL_WIDTH)
        preferences.setWallpaperUri("content://pictures/1")
        preferences.setBackgroundServiceEnabled(false)
        preferences.setAutoLogin(true)

        assertEquals(ThemeMode.LIGHT, preferences.themeMode.first())
        assertEquals(WallpaperScaleMode.FILL_WIDTH, preferences.wallpaperScaleMode.first())

        preferences.clear()

        // Wallpaper and the background-service switch belong to the device, not to
        // the account that was signed in.
        assertEquals("content://pictures/1", preferences.wallpaperUri.first())
        assertEquals(WallpaperScaleMode.FILL_WIDTH, preferences.wallpaperScaleMode.first())
        assertEquals(false, preferences.backgroundServiceEnabled.first())
        // Everything else goes back to its default.
        assertEquals(PreferenceDefaults.AUTO_LOGIN, preferences.autoLogin.first())
        assertEquals(PreferenceDefaults.THEME_MODE, preferences.themeMode.first())
    }
}
