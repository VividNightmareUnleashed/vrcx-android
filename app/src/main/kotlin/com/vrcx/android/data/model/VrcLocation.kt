package com.vrcx.android.data.model

import com.vrcx.android.data.api.model.CurrentUser
import com.vrcx.android.data.api.model.VrcUser

/** The `wrld_…` prefix in a VRChat [location], or null for a sentinel/non-world value. */
fun worldIdOrNull(location: String?): String? = location?.substringBefore(":")?.takeIf { it.startsWith("wrld_") }

/** The world id in [location], or "" when there is none. */
fun parseWorldId(location: String): String = worldIdOrNull(location).orEmpty()

/** Follows a `traveling` presence to the destination it points at. */
fun resolvePresenceLocation(user: CurrentUser?): String = when (user?.location) {
    "traveling" -> user.travelingToLocation.orEmpty()
    else -> user?.location.orEmpty()
}

/** Follows a `traveling` presence to the destination it points at. */
fun resolvePresenceLocation(friend: VrcUser?): String = when (friend?.location) {
    "traveling" -> friend.travelingToLocation.orEmpty()
    else -> friend?.location.orEmpty()
}

/**
 * The presence a [location] implies: the `offline` sentinel and a missing
 * location both mean offline, `private` means present but not joinable, and
 * anything else is a world the user is in.
 *
 * The `offline = false` friends sweep deliberately maps a blank location to
 * [FriendState.ACTIVE] instead, because that endpoint has already said the user
 * is online; that extra knowledge does not belong here.
 */
fun friendStateOf(location: String?): FriendState = when {
    location.isNullOrBlank() || location == "offline" -> FriendState.OFFLINE
    location == "private" -> FriendState.ACTIVE
    else -> FriendState.ONLINE
}

/** True when [location] points at a real, joinable instance. */
fun isTrackableLocation(location: String): Boolean = location.isNotBlank() &&
    location != "offline" &&
    location != "private" &&
    location != "traveling"

/** An `instance 12345` hint, or "" when [location] has no instance segment. */
fun formatInstanceHint(location: String): String {
    val instanceLabel = location.substringAfter(":", "").substringBefore("~")
    return if (instanceLabel.isBlank()) "" else "instance $instanceLabel"
}

/**
 * The world id a user is in — following a `traveling` presence to its
 * destination — or null when the resolved location is a sentinel/non-world.
 */
fun resolvedWorldId(location: String?, travelingToLocation: String?): String? = worldIdOrNull(
    if (location == "traveling") {
        travelingToLocation
    } else {
        location
    },
)
