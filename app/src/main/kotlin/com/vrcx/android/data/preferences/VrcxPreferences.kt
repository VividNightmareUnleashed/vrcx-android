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

@Singleton
class VrcxPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dataStore get() = context.dataStore
    private val preferences = dataStore.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }

    // Notification settings
    val notifyInvite: Flow<Boolean> = preferences.map { it[NOTIFY_INVITE] ?: true }
    val notifyFriendRequest: Flow<Boolean> = preferences.map { it[NOTIFY_FRIEND_REQUEST] ?: true }

    suspend fun setNotifyInvite(enabled: Boolean) = dataStore.edit { it[NOTIFY_INVITE] = enabled }
    suspend fun setNotifyFriendRequest(enabled: Boolean) =
        dataStore.edit { it[NOTIFY_FRIEND_REQUEST] = enabled }

    // Appearance
    val themeMode: Flow<String> = preferences.map { it[THEME_MODE] ?: "dark" }
    val dynamicColors: Flow<Boolean> = preferences.map { it[DYNAMIC_COLORS] ?: false }

    suspend fun setThemeMode(mode: String) = dataStore.edit { it[THEME_MODE] = mode }
    suspend fun setDynamicColors(enabled: Boolean) = dataStore.edit { it[DYNAMIC_COLORS] = enabled }

    val wallpaperUri: Flow<String?> = preferences.map { it[WALLPAPER_URI] }
    suspend fun setWallpaperUri(uri: String?) = dataStore.edit {
        if (uri != null) it[WALLPAPER_URI] = uri else it.remove(WALLPAPER_URI)
    }

    val wallpaperScaleMode: Flow<String> = preferences.map { it[WALLPAPER_SCALE_MODE] ?: "crop" }
    suspend fun setWallpaperScaleMode(mode: String) = dataStore.edit { it[WALLPAPER_SCALE_MODE] = mode }

    // General
    val maxFeedSize: Flow<Int> = preferences.map { it[MAX_FEED_SIZE] ?: 1000 }
    val autoLogin: Flow<Boolean> = preferences.map { it[AUTO_LOGIN] ?: false }

    suspend fun setMaxFeedSize(size: Int) = dataStore.edit { it[MAX_FEED_SIZE] = size }
    suspend fun setAutoLogin(enabled: Boolean) = dataStore.edit { it[AUTO_LOGIN] = enabled }

    val backgroundServiceEnabled: Flow<Boolean> = preferences.map { it[BACKGROUND_SERVICE_ENABLED] ?: true }
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
