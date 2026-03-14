package com.vrcx.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class VrcxColors(
    // Trust rank colors
    val trustVisitor: Color = TrustVisitor,
    val trustNewUser: Color = TrustNewUser,
    val trustUser: Color = TrustUser,
    val trustKnownUser: Color = TrustKnownUser,
    val trustTrustedUser: Color = TrustTrustedUser,
    val trustFriend: Color = TrustFriend,

    // Status colors
    val statusOnline: Color = StatusOnline,
    val statusJoinMe: Color = StatusJoinMe,
    val statusAskMe: Color = StatusAskMe,
    val statusBusy: Color = StatusBusy,
    val statusOffline: Color = StatusOffline,

    // Semantic colors
    val success: Color = VrcxSuccess,
    val warning: Color = VrcxWarning,
    val info: Color = VrcxInfo,

    // Shimmer
    val shimmerBase: Color = Color(0xFF2A2A3C),
    val shimmerHighlight: Color = Color(0xFF3A3A50),
) {
    fun trustColor(trustLevel: String): Color = when (trustLevel) {
        "Visitor" -> trustVisitor
        "New User" -> trustNewUser
        "User" -> trustUser
        "Known User" -> trustKnownUser
        "Trusted User" -> trustTrustedUser
        "Friend" -> trustFriend
        else -> trustVisitor
    }

    fun statusColor(status: String?): Color = when (status?.lowercase()) {
        "join me" -> statusJoinMe
        "active" -> statusOnline
        "ask me" -> statusAskMe
        "busy" -> statusBusy
        else -> statusOffline
    }
}

val LocalVrcxColors = staticCompositionLocalOf { VrcxColors() }
val LocalWallpaperActive = staticCompositionLocalOf { false }

val MaterialTheme.vrcxColors: VrcxColors
    @Composable
    @ReadOnlyComposable
    get() = LocalVrcxColors.current
