package com.vrcx.android.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "vrcx_settings")

/**
 * The theme choices, with the token each one is stored as. The tokens are what
 * already sits in DataStore on every install, so they are not derived from the
 * constant names.
 */
enum class ThemeMode(val token: String, val label: String) {
    SYSTEM("system", "System"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark"),
    ;

    companion object {
        fun fromToken(token: String?): ThemeMode? = entries.firstOrNull { it.token == token }
    }
}

/** How the wallpaper image is scaled, with its stored token. */
enum class WallpaperScaleMode(val token: String, val label: String) {
    CROP("crop", "Crop"),
    FIT("fit", "Fit"),
    FILL_WIDTH("fill_width", "Fill W"),
    FILL_HEIGHT("fill_height", "Fill H"),
    ;

    companion object {
        fun fromToken(token: String?): WallpaperScaleMode? = entries.firstOrNull { it.token == token }
    }
}

/**
 * What every setting is before the user has chosen, in one place. ViewModels seed
 * their StateFlows from here so the value a screen paints before DataStore answers
 * is the value it will keep.
 */
object PreferenceDefaults {
    val THEME_MODE = ThemeMode.DARK
    const val DYNAMIC_COLORS = false
    val WALLPAPER_SCALE_MODE = WallpaperScaleMode.CROP
    const val NOTIFY_INVITE = true
    const val NOTIFY_FRIEND_REQUEST = true
    const val NOTIFY_GENERAL = true
    const val MAX_FEED_SIZE = 1000
    const val AUTO_LOGIN = false
    const val BACKGROUND_SERVICE_ENABLED = true
}

@Singleton
class VrcxPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dataStore get() = context.dataStore
    private val preferences = dataStore.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }

    // Notification settings
    val notifyInvite: Flow<Boolean> = preferences.map {
        it[NOTIFY_INVITE] ?: PreferenceDefaults.NOTIFY_INVITE
    }
    val notifyFriendRequest: Flow<Boolean> = preferences.map {
        it[NOTIFY_FRIEND_REQUEST] ?: PreferenceDefaults.NOTIFY_FRIEND_REQUEST
    }
    /** Covers notification types the app has no category for; their text is whatever the sender wrote. */
    val notifyGeneral: Flow<Boolean> = preferences.map {
        it[NOTIFY_GENERAL] ?: PreferenceDefaults.NOTIFY_GENERAL
    }

    suspend fun setNotifyInvite(enabled: Boolean) = dataStore.edit { it[NOTIFY_INVITE] = enabled }
    suspend fun setNotifyFriendRequest(enabled: Boolean) =
        dataStore.edit { it[NOTIFY_FRIEND_REQUEST] = enabled }
    suspend fun setNotifyGeneral(enabled: Boolean) = dataStore.edit { it[NOTIFY_GENERAL] = enabled }

    // Appearance
    val themeMode: Flow<ThemeMode> = preferences.map {
        ThemeMode.fromToken(it[THEME_MODE]) ?: PreferenceDefaults.THEME_MODE
    }
    val dynamicColors: Flow<Boolean> = preferences.map {
        it[DYNAMIC_COLORS] ?: PreferenceDefaults.DYNAMIC_COLORS
    }

    suspend fun setThemeMode(mode: ThemeMode) = dataStore.edit { it[THEME_MODE] = mode.token }
    suspend fun setDynamicColors(enabled: Boolean) = dataStore.edit { it[DYNAMIC_COLORS] = enabled }

    val wallpaperUri: Flow<String?> = preferences.map { it[WALLPAPER_URI] }
    suspend fun setWallpaperUri(uri: String?) = dataStore.edit {
        if (uri != null) it[WALLPAPER_URI] = uri else it.remove(WALLPAPER_URI)
    }

    val wallpaperScaleMode: Flow<WallpaperScaleMode> = preferences.map {
        WallpaperScaleMode.fromToken(it[WALLPAPER_SCALE_MODE]) ?: PreferenceDefaults.WALLPAPER_SCALE_MODE
    }
    suspend fun setWallpaperScaleMode(mode: WallpaperScaleMode) =
        dataStore.edit { it[WALLPAPER_SCALE_MODE] = mode.token }

    // General
    val maxFeedSize: Flow<Int> = preferences.map { it[MAX_FEED_SIZE] ?: PreferenceDefaults.MAX_FEED_SIZE }
    val autoLogin: Flow<Boolean> = preferences.map { it[AUTO_LOGIN] ?: PreferenceDefaults.AUTO_LOGIN }

    suspend fun setMaxFeedSize(size: Int) = dataStore.edit { it[MAX_FEED_SIZE] = size }
    suspend fun setAutoLogin(enabled: Boolean) = dataStore.edit { it[AUTO_LOGIN] = enabled }

    val backgroundServiceEnabled: Flow<Boolean> = preferences.map {
        it[BACKGROUND_SERVICE_ENABLED] ?: PreferenceDefaults.BACKGROUND_SERVICE_ENABLED
    }
    suspend fun setBackgroundServiceEnabled(enabled: Boolean) = dataStore.edit { it[BACKGROUND_SERVICE_ENABLED] = enabled }

    suspend fun clear() = dataStore.edit { prefs ->
        val wallpaperValue = prefs[WALLPAPER_URI]
        val wallpaperScaleModeValue = prefs[WALLPAPER_SCALE_MODE]
        val backgroundServiceValue = prefs[BACKGROUND_SERVICE_ENABLED]
        prefs.clear()
        if (wallpaperValue != null) prefs[WALLPAPER_URI] = wallpaperValue
        if (wallpaperScaleModeValue != null) prefs[WALLPAPER_SCALE_MODE] = wallpaperScaleModeValue
        if (backgroundServiceValue != null) prefs[BACKGROUND_SERVICE_ENABLED] = backgroundServiceValue
    }

    companion object {
        private val NOTIFY_INVITE = booleanPreferencesKey("notify_invite")
        private val NOTIFY_FRIEND_REQUEST = booleanPreferencesKey("notify_friend_request")
        private val NOTIFY_GENERAL = booleanPreferencesKey("notify_general")
        private val THEME_MODE = stringPreferencesKey("theme_mode")
        private val DYNAMIC_COLORS = booleanPreferencesKey("dynamic_colors")
        private val MAX_FEED_SIZE = intPreferencesKey("max_feed_size")
        private val AUTO_LOGIN = booleanPreferencesKey("auto_login")
        private val WALLPAPER_URI = stringPreferencesKey("wallpaper_uri")
        private val WALLPAPER_SCALE_MODE = stringPreferencesKey("wallpaper_scale_mode")
        private val BACKGROUND_SERVICE_ENABLED = booleanPreferencesKey("background_service_enabled")
        private val SAVED_USERNAME = stringPreferencesKey("saved_username")
        private val SAVED_PASSWORD = stringPreferencesKey("saved_password")
    }

    suspend fun getLegacySavedCredentials(): Pair<String, String>? {
        val prefs = preferences.map { data ->
            val username = data[SAVED_USERNAME]
            val password = data[SAVED_PASSWORD]
            if (username.isNullOrEmpty() || password.isNullOrEmpty()) {
                null
            } else {
                username to password
            }
        }
        return prefs.map { it }.firstOrNull()
    }

    suspend fun clearLegacySavedCredentials() {
        dataStore.edit {
            it.remove(SAVED_USERNAME)
            it.remove(SAVED_PASSWORD)
        }
    }
}
