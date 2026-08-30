package com.vrcx.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

val LocalVrcxColors = staticCompositionLocalOf { DarkVrcxColors }
val LocalWallpaperActive = staticCompositionLocalOf { false }

val MaterialTheme.vrcxColors: VrcxColors
    @Composable
    @ReadOnlyComposable
    get() = LocalVrcxColors.current
