package com.vrcx.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.vrcx.android.data.model.FriendState
import com.vrcx.android.ui.theme.StatusBusy
import com.vrcx.android.ui.theme.StatusJoinMe
import com.vrcx.android.ui.theme.StatusOffline
import com.vrcx.android.ui.theme.StatusOnline
import com.vrcx.android.ui.theme.vrcxColors

fun statusColor(status: String?, state: FriendState): Color {
    if (state == FriendState.OFFLINE) return StatusOffline
    return when (status) {
        "join me" -> StatusJoinMe
        "active" -> StatusOnline
        "ask me" -> com.vrcx.android.ui.theme.StatusAskMe
        "busy" -> StatusBusy
        else -> StatusOnline
    }
}

@Composable
fun UserAvatar(
    imageUrl: String?,
    status: String? = null,
    state: FriendState = FriendState.ONLINE,
    size: Dp = 48.dp,
    showStatusDot: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val vrcxColors = MaterialTheme.vrcxColors

    Box(modifier = modifier) {
        if (imageUrl.isNullOrEmpty()) {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(vrcxColors.panelHover),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(size * 0.6f),
                    tint = vrcxColors.panelMuted,
                )
            }
        } else {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(vrcxColors.panelHover),
                contentScale = ContentScale.Crop,
            )
        }
        if (showStatusDot) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .align(Alignment.BottomEnd)
                    .clip(CircleShape)
                    .background(statusColor(status, state))
                    .border(2.dp, vrcxColors.panelBackground, CircleShape),
            )
        }
    }
}
