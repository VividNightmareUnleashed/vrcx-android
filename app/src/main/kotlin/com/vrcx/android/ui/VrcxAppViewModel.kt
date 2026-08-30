package com.vrcx.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.preferences.PreferenceDefaults
import com.vrcx.android.data.preferences.ThemeMode
import com.vrcx.android.data.preferences.VrcxPreferences
import com.vrcx.android.data.preferences.WallpaperScaleMode
import com.vrcx.android.ui.common.whileUiSubscribed
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class VrcxAppViewModel @Inject constructor(preferences: VrcxPreferences) : ViewModel() {
    private val sharingStarted = whileUiSubscribed

    // Null represents unresolved DataStore state so cold start follows the
    // already-visible system theme instead of flashing an arbitrary default.
    val themeMode: StateFlow<ThemeMode?> =
        preferences.themeMode.stateIn(viewModelScope, sharingStarted, null)
    val dynamicColors: StateFlow<Boolean> =
        preferences.dynamicColors.stateIn(
            viewModelScope,
            sharingStarted,
            PreferenceDefaults.DYNAMIC_COLORS,
        )
    val wallpaperUri: StateFlow<String?> =
        preferences.wallpaperUri.stateIn(viewModelScope, sharingStarted, null)
    val wallpaperScaleMode: StateFlow<WallpaperScaleMode> =
        preferences.wallpaperScaleMode.stateIn(
            viewModelScope,
            sharingStarted,
            PreferenceDefaults.WALLPAPER_SCALE_MODE,
        )
    val backgroundServiceEnabled: StateFlow<Boolean?> =
        preferences.backgroundServiceEnabled.stateIn(viewModelScope, sharingStarted, null)
}
