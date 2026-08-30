package com.vrcx.android.data.preferences

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface GeneralPreferences {
    val maxFeedSize: Flow<Int>
    val autoLogin: Flow<Boolean>
    val backgroundServiceEnabled: Flow<Boolean>

    suspend fun setMaxFeedSize(size: Int): Preferences
    suspend fun setAutoLogin(enabled: Boolean): Preferences
    suspend fun setBackgroundServiceEnabled(enabled: Boolean): Preferences
}

internal class StoredGeneralPreferences(private val source: PreferenceSource) : GeneralPreferences {
    override val maxFeedSize: Flow<Int> = source.values.map {
        it[PreferenceKeys.maxFeedSize] ?: PreferenceDefaults.MAX_FEED_SIZE
    }
    override val autoLogin: Flow<Boolean> = source.values.map {
        it[PreferenceKeys.autoLogin] ?: PreferenceDefaults.AUTO_LOGIN
    }
    override val backgroundServiceEnabled: Flow<Boolean> = source.dataStore.data.map {
        it[PreferenceKeys.backgroundServiceEnabled] ?: PreferenceDefaults.BACKGROUND_SERVICE_ENABLED
    }.recoverIOExceptionWith(false)

    override suspend fun setMaxFeedSize(size: Int): Preferences = source.dataStore.edit {
        it[PreferenceKeys.maxFeedSize] = size
    }

    override suspend fun setAutoLogin(enabled: Boolean): Preferences = source.dataStore.edit {
        it[PreferenceKeys.autoLogin] = enabled
    }

    override suspend fun setBackgroundServiceEnabled(enabled: Boolean): Preferences = source.dataStore.edit {
        it[PreferenceKeys.backgroundServiceEnabled] = enabled
    }
}
