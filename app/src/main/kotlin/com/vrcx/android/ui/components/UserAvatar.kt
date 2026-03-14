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
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        AsyncImage(
            model = imageUrl,
            contentDescription = null,
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(Color.Gray.copy(alpha = 0.3f)),
            contentScale = ContentScale.Crop,
        )
        // Status indicator dot
        Box(
            modifier = Modifier
                .size(12.dp)
                .align(Alignment.BottomEnd)
                .clip(CircleShape)
                .background(statusColor(status, state))
                .border(2.dp, Color.White, CircleShape),
        )
    }
}
