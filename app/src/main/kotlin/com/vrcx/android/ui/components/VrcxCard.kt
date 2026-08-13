package com.vrcx.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    val cardShape = RoundedCornerShape(12.dp)
    val colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            .let { if (isWallpaperActive) it.copy(alpha = 0.86f) else it },
    )
    Card(
        modifier = modifier
            .fillMaxWidth()
            // Clip before the click so the ripple stays inside the rounded
            // corners, matching UserListItem and WorldListItem.
            .then(
                if (onClick != null) {
                    Modifier.clip(cardShape).clickable(onClick = onClick)
                } else {
                    Modifier
                },
            ),
        shape = cardShape,
        colors = colors,
        border = BorderStroke(1.dp, vrcxColors.panelBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        content()
    }
}
