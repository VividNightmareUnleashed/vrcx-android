package com.vrcx.android.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "vrcx_settings")

@Singleton
class VrcxPreferences @Inject constructor(
    private val context: Context,
) {
    private val dataStore get() = context.dataStore

    // Auth
    val lastUserId: Flow<String?> = dataStore.data.map { it[LAST_USER_ID] }
    suspend fun setLastUserId(userId: String) = dataStore.edit { it[LAST_USER_ID] = userId }

    // Notification settings
    val notifyInvite: Flow<Boolean> = dataStore.data.map { it[NOTIFY_INVITE] ?: true }
    val notifyFriendRequest: Flow<Boolean> = dataStore.data.map { it[NOTIFY_FRIEND_REQUEST] ?: true }

    suspend fun setNotifySetting(key: Preferences.Key<Boolean>, value: Boolean) {
        dataStore.edit { it[key] = value }
    }

    // Appearance
    val themeMode: Flow<String> = dataStore.data.map { it[THEME_MODE] ?: "system" }
    val dynamicColors: Flow<Boolean> = dataStore.data.map { it[DYNAMIC_COLORS] ?: true }

    suspend fun setThemeMode(mode: String) = dataStore.edit { it[THEME_MODE] = mode }
    suspend fun setDynamicColors(enabled: Boolean) = dataStore.edit { it[DYNAMIC_COLORS] = enabled }

    val wallpaperUri: Flow<String?> = dataStore.data.map { it[WALLPAPER_URI] }
    suspend fun setWallpaperUri(uri: String?) = dataStore.edit {
        if (uri != null) it[WALLPAPER_URI] = uri else it.remove(WALLPAPER_URI)
    }

    val wallpaperScaleMode: Flow<String> = dataStore.data.map { it[WALLPAPER_SCALE_MODE] ?: "crop" }
    suspend fun setWallpaperScaleMode(mode: String) = dataStore.edit { it[WALLPAPER_SCALE_MODE] = mode }

    // General
    val maxFeedSize: Flow<Int> = dataStore.data.map { it[MAX_FEED_SIZE] ?: 1000 }
    val autoLogin: Flow<Boolean> = dataStore.data.map { it[AUTO_LOGIN] ?: false }

    suspend fun setMaxFeedSize(size: Int) = dataStore.edit { it[MAX_FEED_SIZE] = size }
    suspend fun setAutoLogin(enabled: Boolean) = dataStore.edit { it[AUTO_LOGIN] = enabled }

    val backgroundServiceEnabled: Flow<Boolean> = dataStore.data.map { it[BACKGROUND_SERVICE_ENABLED] ?: true }
    suspend fun setBackgroundServiceEnabled(enabled: Boolean) = dataStore.edit { it[BACKGROUND_SERVICE_ENABLED] = enabled }

    // Disclaimer
    val disclaimerAccepted: Flow<Boolean> = dataStore.data.map { it[DISCLAIMER_ACCEPTED] ?: false }
    suspend fun setDisclaimerAccepted(accepted: Boolean) = dataStore.edit { it[DISCLAIMER_ACCEPTED] = accepted }

    suspend fun clear() = dataStore.edit { prefs ->
        val disclaimerValue = prefs[DISCLAIMER_ACCEPTED]
        val wallpaperValue = prefs[WALLPAPER_URI]
        val wallpaperScaleModeValue = prefs[WALLPAPER_SCALE_MODE]
        val backgroundServiceValue = prefs[BACKGROUND_SERVICE_ENABLED]
        prefs.clear()
        if (disclaimerValue != null) prefs[DISCLAIMER_ACCEPTED] = disclaimerValue
        if (wallpaperValue != null) prefs[WALLPAPER_URI] = wallpaperValue
        if (wallpaperScaleModeValue != null) prefs[WALLPAPER_SCALE_MODE] = wallpaperScaleModeValue
        if (backgroundServiceValue != null) prefs[BACKGROUND_SERVICE_ENABLED] = backgroundServiceValue
    }

    companion object {
        val LAST_USER_ID = stringPreferencesKey("last_user_id")
        val NOTIFY_INVITE = booleanPreferencesKey("notify_invite")
        val NOTIFY_FRIEND_REQUEST = booleanPreferencesKey("notify_friend_request")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLORS = booleanPreferencesKey("dynamic_colors")
        val MAX_FEED_SIZE = intPreferencesKey("max_feed_size")
        val AUTO_LOGIN = booleanPreferencesKey("auto_login")
        val DISCLAIMER_ACCEPTED = booleanPreferencesKey("disclaimer_accepted")
        val WALLPAPER_URI = stringPreferencesKey("wallpaper_uri")
        val WALLPAPER_SCALE_MODE = stringPreferencesKey("wallpaper_scale_mode")
        val BACKGROUND_SERVICE_ENABLED = booleanPreferencesKey("background_service_enabled")
        private val SAVED_USERNAME = stringPreferencesKey("saved_username")
        private val SAVED_PASSWORD = stringPreferencesKey("saved_password")
    }

    suspend fun getLegacySavedCredentials(): Pair<String, String>? {
        val prefs = dataStore.data.map { data ->
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
