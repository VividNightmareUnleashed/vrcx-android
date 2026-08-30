package com.vrcx.android.ui.theme

import androidx.compose.ui.graphics.Color
import com.vrcx.android.data.model.FriendState

data class VrcxColors(
    val trustVisitor: Color = TrustVisitor,
    val trustNewUser: Color = TrustNewUser,
    val trustUser: Color = TrustUser,
    val trustKnownUser: Color = TrustKnownUser,
    val trustTrustedUser: Color = TrustTrustedUser,
    val trustFriend: Color = TrustFriend,
    val statusOnline: Color = StatusOnline,
    val statusJoinMe: Color = StatusJoinMe,
    val statusAskMe: Color = StatusAskMe,
    val statusBusy: Color = StatusBusy,
    val statusOffline: Color = StatusOffline,
    // These have no defaults because Theme.kt owns both complete palettes.
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

            // VRChat can report "offline" as a profile status for a friend
            // still present in the active list; reflect that explicit status.
            "offline" -> statusOffline

            else -> statusOnline
        }
    }
}
