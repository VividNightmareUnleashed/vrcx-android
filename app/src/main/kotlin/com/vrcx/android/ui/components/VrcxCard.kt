package com.vrcx.android.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vrcx.android.ui.theme.LocalWallpaperActive
import com.vrcx.android.ui.theme.vrcxColors

@Composable
fun VrcxCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val isWallpaperActive = LocalWallpaperActive.current
    val vrcxColors = MaterialTheme.vrcxColors
    val colors = CardDefaults.cardColors(
        containerColor = vrcxColors.panelElevated
            .let { if (isWallpaperActive) it.copy(alpha = 0.86f) else it },
    )
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = colors,
            border = BorderStroke(1.dp, vrcxColors.panelBorder),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
        ) {
            content()
        }
    } else {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = colors,
            border = BorderStroke(1.dp, vrcxColors.panelBorder),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            content()
        }
    }
}
