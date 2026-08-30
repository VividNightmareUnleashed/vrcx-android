package com.vrcx.android.data.preferences

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map

interface SessionPreferences {
    suspend fun clear(): Preferences
    suspend fun getLegacySavedCredentials(): Pair<String, String>?
    suspend fun clearLegacySavedCredentials()
}

internal class StoredSessionPreferences(private val source: PreferenceSource) : SessionPreferences {
    override suspend fun clear(): Preferences = source.dataStore.edit { preferences ->
        val wallpaperUri = preferences[PreferenceKeys.wallpaperUri]
        val wallpaperScaleMode = preferences[PreferenceKeys.wallpaperScaleMode]
        val backgroundServiceEnabled = preferences[PreferenceKeys.backgroundServiceEnabled]
        preferences.clear()
        if (wallpaperUri != null) preferences[PreferenceKeys.wallpaperUri] = wallpaperUri
        if (wallpaperScaleMode != null) {
            preferences[PreferenceKeys.wallpaperScaleMode] = wallpaperScaleMode
        }
        if (backgroundServiceEnabled != null) {
            preferences[PreferenceKeys.backgroundServiceEnabled] = backgroundServiceEnabled
        }
    }

    override suspend fun getLegacySavedCredentials(): Pair<String, String>? = source.values.map { preferences ->
        val username = preferences[PreferenceKeys.savedUsername]
        val password = preferences[PreferenceKeys.savedPassword]
        if (username.isNullOrEmpty() || password.isNullOrEmpty()) null else username to password
    }.firstOrNull()

    override suspend fun clearLegacySavedCredentials() {
        source.dataStore.edit {
            it.remove(PreferenceKeys.savedUsername)
            it.remove(PreferenceKeys.savedPassword)
        }
    }
}
