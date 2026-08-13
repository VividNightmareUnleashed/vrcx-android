package com.vrcx.android.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The tokens are what is already written into DataStore on every install, so
 * renaming a constant must not rename what is stored.
 */
class PreferenceVocabularyTest {

    @Test
    fun `theme tokens are the ones already on disk`() {
        assertEquals(listOf("system", "light", "dark"), ThemeMode.entries.map { it.token })
    }

    @Test
    fun `wallpaper scale tokens are the ones already on disk`() {
        assertEquals(
            listOf("crop", "fit", "fill_width", "fill_height"),
            WallpaperScaleMode.entries.map { it.token },
        )
    }

    @Test
    fun `every token round-trips back to its own value`() {
        ThemeMode.entries.forEach { assertEquals(it, ThemeMode.fromToken(it.token)) }
        WallpaperScaleMode.entries.forEach { assertEquals(it, WallpaperScaleMode.fromToken(it.token)) }
    }

    @Test
    fun `an unset or unrecognised token is not a value`() {
        // Callers fall back to the default rather than guessing, which is what
        // makes a downgrade or a hand-edited store harmless.
        assertNull(ThemeMode.fromToken(null))
        assertNull(ThemeMode.fromToken(""))
        assertNull(ThemeMode.fromToken("DARK"))
        assertNull(WallpaperScaleMode.fromToken("fillWidth"))
    }

    @Test
    fun `the defaults are the ones the app shipped with`() {
        assertEquals(ThemeMode.DARK, PreferenceDefaults.THEME_MODE)
        assertEquals(WallpaperScaleMode.CROP, PreferenceDefaults.WALLPAPER_SCALE_MODE)
        assertEquals(1000, PreferenceDefaults.MAX_FEED_SIZE)
        assertEquals(false, PreferenceDefaults.DYNAMIC_COLORS)
        assertEquals(false, PreferenceDefaults.AUTO_LOGIN)
        assertEquals(true, PreferenceDefaults.NOTIFY_INVITE)
        assertEquals(true, PreferenceDefaults.NOTIFY_FRIEND_REQUEST)
        assertEquals(true, PreferenceDefaults.NOTIFY_GENERAL)
        assertEquals(true, PreferenceDefaults.BACKGROUND_SERVICE_ENABLED)
    }
}
