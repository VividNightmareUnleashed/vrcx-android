package com.vrcx.android.ui.screen.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.cache.ProfilePicCacheManager
import com.vrcx.android.data.preferences.PreferenceDefaults
import com.vrcx.android.data.preferences.ThemeMode
import com.vrcx.android.data.preferences.VrcxPreferences
import com.vrcx.android.data.preferences.WallpaperScaleMode
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.FriendRepository
import com.vrcx.android.di.IoDispatcher
import com.vrcx.android.ui.common.formatByteCount
import com.vrcx.android.ui.common.whileUiSubscribed
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** How far a "Cache All Friends" sweep has got; null while none is running. */
data class CacheProgress(val completed: Int, val total: Int)

data class SettingsAppearanceState(
    val themeMode: ThemeMode = PreferenceDefaults.THEME_MODE,
    val dynamicColors: Boolean = PreferenceDefaults.DYNAMIC_COLORS,
    val wallpaperUri: String? = null,
    val wallpaperScaleMode: WallpaperScaleMode = PreferenceDefaults.WALLPAPER_SCALE_MODE,
)

data class SettingsGeneralState(
    val backgroundServiceEnabled: Boolean = PreferenceDefaults.BACKGROUND_SERVICE_ENABLED,
    val maxFeedSize: Int = PreferenceDefaults.MAX_FEED_SIZE,
    val autoLogin: Boolean = PreferenceDefaults.AUTO_LOGIN,
)

data class SettingsNotificationState(
    val notifyInvite: Boolean = PreferenceDefaults.NOTIFY_INVITE,
    val notifyFriendRequest: Boolean = PreferenceDefaults.NOTIFY_FRIEND_REQUEST,
    val notifyGeneral: Boolean = PreferenceDefaults.NOTIFY_GENERAL,
)

data class SettingsStorageState(val cacheSizeText: String = "", val cacheAllProgress: CacheProgress? = null)

data class SettingsUiState(
    val appearance: SettingsAppearanceState = SettingsAppearanceState(),
    val general: SettingsGeneralState = SettingsGeneralState(),
    val notifications: SettingsNotificationState = SettingsNotificationState(),
    val storage: SettingsStorageState = SettingsStorageState(),
)

sealed interface SettingsPreferenceUpdate {
    data class Theme(val mode: ThemeMode) : SettingsPreferenceUpdate

    data class DynamicColors(val enabled: Boolean) : SettingsPreferenceUpdate

    data class Wallpaper(val uri: String?) : SettingsPreferenceUpdate

    data class WallpaperScale(val mode: WallpaperScaleMode) : SettingsPreferenceUpdate

    data class BackgroundService(val enabled: Boolean) : SettingsPreferenceUpdate

    data class FeedHistoryLimit(val size: Int) : SettingsPreferenceUpdate

    data class AutoLogin(val enabled: Boolean) : SettingsPreferenceUpdate

    data class InviteNotifications(val enabled: Boolean) : SettingsPreferenceUpdate

    data class FriendRequestNotifications(val enabled: Boolean) : SettingsPreferenceUpdate

    data class GeneralNotifications(val enabled: Boolean) : SettingsPreferenceUpdate
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: VrcxPreferences,
    private val profilePicCacheManager: ProfilePicCacheManager,
    private val friendRepository: FriendRepository,
    private val authRepository: AuthRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val cacheSizeText = MutableStateFlow("")
    private val cacheAllProgress = MutableStateFlow<CacheProgress?>(null)

    private val appearanceState = combine(
        preferences.themeMode,
        preferences.dynamicColors,
        preferences.wallpaperUri,
        preferences.wallpaperScaleMode,
    ) { themeMode, dynamicColors, wallpaperUri, wallpaperScaleMode ->
        SettingsAppearanceState(themeMode, dynamicColors, wallpaperUri, wallpaperScaleMode)
    }

    private val generalState = combine(
        preferences.backgroundServiceEnabled,
        preferences.maxFeedSize,
        preferences.autoLogin,
    ) { backgroundServiceEnabled, maxFeedSize, autoLogin ->
        SettingsGeneralState(backgroundServiceEnabled, maxFeedSize, autoLogin)
    }

    private val notificationState = combine(
        preferences.notifyInvite,
        preferences.notifyFriendRequest,
        preferences.notifyGeneral,
    ) { notifyInvite, notifyFriendRequest, notifyGeneral ->
        SettingsNotificationState(notifyInvite, notifyFriendRequest, notifyGeneral)
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        appearanceState,
        generalState,
        notificationState,
        cacheSizeText,
        cacheAllProgress,
    ) { appearance, general, notifications, cacheSize, cacheProgress ->
        SettingsUiState(
            appearance = appearance,
            general = general,
            notifications = notifications,
            storage = SettingsStorageState(cacheSize, cacheProgress),
        )
    }.stateIn(viewModelScope, whileUiSubscribed, SettingsUiState())

    fun updatePreference(update: SettingsPreferenceUpdate) = writePreference {
        when (update) {
            is SettingsPreferenceUpdate.Theme -> preferences.setThemeMode(update.mode)

            is SettingsPreferenceUpdate.DynamicColors -> preferences.setDynamicColors(update.enabled)

            is SettingsPreferenceUpdate.Wallpaper -> preferences.setWallpaperUri(update.uri)

            is SettingsPreferenceUpdate.WallpaperScale -> preferences.setWallpaperScaleMode(update.mode)

            is SettingsPreferenceUpdate.BackgroundService -> {
                preferences.setBackgroundServiceEnabled(update.enabled)
            }

            is SettingsPreferenceUpdate.FeedHistoryLimit -> preferences.setMaxFeedSize(update.size)

            is SettingsPreferenceUpdate.AutoLogin -> preferences.setAutoLogin(update.enabled)

            is SettingsPreferenceUpdate.InviteNotifications -> preferences.setNotifyInvite(update.enabled)

            is SettingsPreferenceUpdate.FriendRequestNotifications -> {
                preferences.setNotifyFriendRequest(update.enabled)
            }

            is SettingsPreferenceUpdate.GeneralNotifications -> preferences.setNotifyGeneral(update.enabled)
        }
    }

    fun refreshCacheSize() {
        viewModelScope.launch(ioDispatcher) {
            runLogged("Reading the profile picture cache size failed") { updateCacheSize() }
        }
    }

    fun clearProfilePicCache() {
        viewModelScope.launch(ioDispatcher) {
            runLogged("Clearing the profile picture cache failed") {
                profilePicCacheManager.clearCache()
                updateCacheSize()
            }
        }
    }

    fun cacheAllFriends() {
        viewModelScope.launch(ioDispatcher) {
            try {
                runLogged("Caching all friend pictures failed") {
                    val friends = friendRepository.friends.value
                    profilePicCacheManager.cacheAllFriends(friends) { completed, total ->
                        cacheAllProgress.value = CacheProgress(completed, total)
                    }
                }
            } finally {
                cacheAllProgress.value = null
            }
            runLogged("Reading the profile picture cache size failed") { updateCacheSize() }
        }
    }

    fun signOut() {
        viewModelScope.launch { authRepository.logout() }
    }

    private fun writePreference(write: suspend () -> Unit) {
        viewModelScope.launch {
            runLogged("Preference write failed", write)
        }
    }

    private fun updateCacheSize() {
        cacheSizeText.value = formatByteCount(profilePicCacheManager.getCacheSizeBytes())
    }

    private suspend fun runLogged(message: String, action: suspend () -> Unit) {
        val failure = runCatching { action() }.exceptionOrNull()
        when (failure) {
            null -> Unit
            is CancellationException -> throw failure
            is Exception -> Log.w(TAG, message, failure)
            else -> throw failure
        }
    }

    private companion object {
        const val TAG = "SettingsViewModel"
    }
}
