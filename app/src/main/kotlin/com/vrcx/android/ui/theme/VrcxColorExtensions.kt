package com.vrcx.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.vrcx.android.data.model.FriendState

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

    // Desktop-inspired shell colors. No defaults: Theme.kt's DarkVrcxColors and
    // LightVrcxColors are the only palettes, so a new shell colour has to be
    // given a value there rather than silently inheriting a stale literal.
    val shellGradientStart: Color,
    val shellGradientEnd: Color,
    val panelBackground: Color,
    val panelElevated: Color,
    val panelHover: Color,
    val panelBorder: Color,
    val panelMuted: Color,
    val fieldBackground: Color,
    val focusRing: Color,
    val navActive: Color,
    val navActiveContent: Color,
    val navInactiveContent: Color,
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

    fun statusColor(status: String?, state: FriendState = FriendState.ONLINE): Color {
        if (state == FriendState.OFFLINE) return statusOffline
        return when (status?.lowercase()) {
            "join me" -> statusJoinMe
            "active" -> statusOnline
            "ask me" -> statusAskMe
            "busy" -> statusBusy
            // VRChat also reports "offline" as a profile status — a friend the
            // friends page still lists as active. Honour it rather than painting
            // the at-a-glance dot green.
            "offline" -> statusOffline
            else -> statusOnline
        }
    }
}

val LocalVrcxColors = staticCompositionLocalOf { DarkVrcxColors }
val LocalWallpaperActive = staticCompositionLocalOf { false }

val MaterialTheme.vrcxColors: VrcxColors
    @Composable
    @ReadOnlyComposable
    get() = LocalVrcxColors.current
