package com.vrcx.android.data.preferences

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface AppearancePreferences {
    val themeMode: Flow<ThemeMode>
    val dynamicColors: Flow<Boolean>
    val wallpaperUri: Flow<String?>
    val wallpaperScaleMode: Flow<WallpaperScaleMode>

    suspend fun setThemeMode(mode: ThemeMode): Preferences
    suspend fun setDynamicColors(enabled: Boolean): Preferences
    suspend fun setWallpaperUri(uri: String?): Preferences
    suspend fun setWallpaperScaleMode(mode: WallpaperScaleMode): Preferences
}

internal class StoredAppearancePreferences(private val source: PreferenceSource) : AppearancePreferences {
    override val themeMode: Flow<ThemeMode> = source.values.map {
        ThemeMode.fromToken(it[PreferenceKeys.themeMode]) ?: PreferenceDefaults.THEME_MODE
    }
    override val dynamicColors: Flow<Boolean> = source.values.map {
        it[PreferenceKeys.dynamicColors] ?: PreferenceDefaults.DYNAMIC_COLORS
    }
    override val wallpaperUri: Flow<String?> = source.values.map { it[PreferenceKeys.wallpaperUri] }
    override val wallpaperScaleMode: Flow<WallpaperScaleMode> = source.values.map {
        WallpaperScaleMode.fromToken(it[PreferenceKeys.wallpaperScaleMode])
            ?: PreferenceDefaults.WALLPAPER_SCALE_MODE
    }

    override suspend fun setThemeMode(mode: ThemeMode): Preferences = source.dataStore.edit {
        it[PreferenceKeys.themeMode] = mode.token
    }

    override suspend fun setDynamicColors(enabled: Boolean): Preferences = source.dataStore.edit {
        it[PreferenceKeys.dynamicColors] = enabled
    }

    override suspend fun setWallpaperUri(uri: String?): Preferences = source.dataStore.edit {
        if (uri != null) it[PreferenceKeys.wallpaperUri] = uri else it.remove(PreferenceKeys.wallpaperUri)
    }

    override suspend fun setWallpaperScaleMode(mode: WallpaperScaleMode): Preferences = source.dataStore.edit {
        it[PreferenceKeys.wallpaperScaleMode] = mode.token
    }
}
