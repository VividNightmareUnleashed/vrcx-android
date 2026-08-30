@file:OptIn(ExperimentalMaterial3Api::class)

package com.vrcx.android.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vrcx.android.data.preferences.ThemeMode
import com.vrcx.android.data.preferences.WallpaperScaleMode

@Composable
internal fun AppearanceSettingsSection(
    state: SettingsAppearanceState,
    onPreferenceUpdate: (SettingsPreferenceUpdate) -> Unit,
    onSelectWallpaper: () -> Unit,
) {
    SettingsSection("Appearance") {
        Text("Theme", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Choose light, dark, or system default",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        ThemeModeSelector(state.themeMode, onPreferenceUpdate)
        SettingToggle(
            title = "Dynamic Colors",
            subtitle = "Use Material You colors",
            checked = state.dynamicColors,
            onCheckedChange = {
                onPreferenceUpdate(SettingsPreferenceUpdate.DynamicColors(it))
            },
        )
        WallpaperSettings(state, onPreferenceUpdate, onSelectWallpaper)
    }
}

@Composable
private fun ThemeModeSelector(selectedMode: ThemeMode, onPreferenceUpdate: (SettingsPreferenceUpdate) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        ThemeMode.entries.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = selectedMode == mode,
                onClick = { onPreferenceUpdate(SettingsPreferenceUpdate.Theme(mode)) },
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = ThemeMode.entries.size,
                ),
            ) { Text(mode.label) }
        }
    }
}

@Composable
private fun WallpaperSettings(
    state: SettingsAppearanceState,
    onPreferenceUpdate: (SettingsPreferenceUpdate) -> Unit,
    onSelectWallpaper: () -> Unit,
) {
    val hasWallpaper = state.wallpaperUri != null
    Text("Wallpaper", style = MaterialTheme.typography.bodyLarge)
    Text(
        if (hasWallpaper) "Custom wallpaper set" else "No wallpaper",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = onSelectWallpaper) {
            Text(if (hasWallpaper) "Change Wallpaper" else "Set Wallpaper")
        }
        if (hasWallpaper) {
            OutlinedButton(
                onClick = { onPreferenceUpdate(SettingsPreferenceUpdate.Wallpaper(null)) },
            ) {
                Text("Remove")
            }
        }
    }
    if (hasWallpaper) {
        WallpaperScaleModeSelector(state.wallpaperScaleMode, onPreferenceUpdate)
    }
}

@Composable
private fun WallpaperScaleModeSelector(
    selectedMode: WallpaperScaleMode,
    onPreferenceUpdate: (SettingsPreferenceUpdate) -> Unit,
) {
    Text("Scale Mode", style = MaterialTheme.typography.bodyLarge)
    Text(
        "How the wallpaper image is scaled",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        WallpaperScaleMode.entries.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = selectedMode == mode,
                onClick = { onPreferenceUpdate(SettingsPreferenceUpdate.WallpaperScale(mode)) },
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = WallpaperScaleMode.entries.size,
                ),
            ) { Text(mode.label) }
        }
    }
}
