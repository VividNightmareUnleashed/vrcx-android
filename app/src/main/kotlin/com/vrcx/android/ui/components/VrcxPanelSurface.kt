package com.vrcx.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vrcx.android.ui.theme.LocalWallpaperActive
import com.vrcx.android.ui.theme.vrcxColors

@Composable
fun VrcxPanelSurface(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val isWallpaperActive = LocalWallpaperActive.current
    val vrcxColors = MaterialTheme.vrcxColors

    Surface(
        modifier = modifier.fillMaxSize(),
        shape = MaterialTheme.shapes.medium,
        color = vrcxColors.panelBackground.let {
            if (isWallpaperActive) it.copy(alpha = 0.82f) else it
        },
        border = BorderStroke(1.dp, vrcxColors.panelBorder),
        shadowElevation = 0.dp,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            content = content,
        )
    }
}
