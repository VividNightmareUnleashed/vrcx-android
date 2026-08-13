package com.vrcx.android.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.vrcx.android.ui.theme.LocalWallpaperActive

/**
 * The app's tab strip. Owns its wallpaper policy the way [VrcxCard] and
 * [VrcxTopBar] own theirs, so a screen passes only the selection and its tabs.
 */
@Composable
fun VrcxTabRow(
    selectedTabIndex: Int,
    modifier: Modifier = Modifier,
    tabs: @Composable () -> Unit,
) {
    TabRow(
        selectedTabIndex = selectedTabIndex,
        modifier = modifier,
        containerColor = tabStripContainerColor(),
        tabs = tabs,
    )
}

/** [VrcxTabRow] for strips with more tabs than fit the width. */
@Composable
fun VrcxScrollableTabRow(
    selectedTabIndex: Int,
    modifier: Modifier = Modifier,
    edgePadding: Dp = TabRowDefaults.ScrollableTabRowEdgeStartPadding,
    tabs: @Composable () -> Unit,
) {
    ScrollableTabRow(
        selectedTabIndex = selectedTabIndex,
        modifier = modifier,
        containerColor = tabStripContainerColor(),
        edgePadding = edgePadding,
        tabs = tabs,
    )
}

@Composable
private fun tabStripContainerColor(): Color =
    tabStripContainerColor(MaterialTheme.colorScheme.surfaceContainer, LocalWallpaperActive.current)

/** Lets a wallpaper through the strip, and stays opaque when there is none. */
internal fun tabStripContainerColor(base: Color, isWallpaperActive: Boolean): Color =
    if (isWallpaperActive) base.copy(alpha = 0.88f) else base
