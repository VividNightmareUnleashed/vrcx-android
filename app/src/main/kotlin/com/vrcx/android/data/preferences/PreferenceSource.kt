package com.vrcx.android.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch

/** Shared DataStore access, including the default recovery policy used by ordinary settings. */
internal class PreferenceSource(val dataStore: DataStore<Preferences>) {
    val values: Flow<Preferences> = dataStore.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }
}

internal object PreferenceKeys {
    val notifyInvite = booleanPreferencesKey("notify_invite")
    val notifyFriendRequest = booleanPreferencesKey("notify_friend_request")
    val notifyGeneral = booleanPreferencesKey("notify_general")
    val themeMode = stringPreferencesKey("theme_mode")
    val dynamicColors = booleanPreferencesKey("dynamic_colors")
    val maxFeedSize = intPreferencesKey("max_feed_size")
    val autoLogin = booleanPreferencesKey("auto_login")
    val wallpaperUri = stringPreferencesKey("wallpaper_uri")
    val wallpaperScaleMode = stringPreferencesKey("wallpaper_scale_mode")
    val backgroundServiceEnabled = booleanPreferencesKey("background_service_enabled")
    val savedUsername = stringPreferencesKey("saved_username")
    val savedPassword = stringPreferencesKey("saved_password")
}
