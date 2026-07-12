package com.vrcx.android.data.model

import com.vrcx.android.data.api.model.CurrentUser
import com.vrcx.android.data.api.model.VrcUser

/**
 * The VRChat location-string grammar in one place. A location looks like
 * `wrld_xxx:instanceId~region(...)`, with the sentinels `offline`, `private`,
 * and `traveling` standing in for a presence that can't be joined directly.
 *
 * These helpers were previously copy-pasted as private functions across the
 * friends-locations, game-log, player-list and profile screens; keeping the
 * grammar here lets repositories reuse it and stops the copies from drifting.
 */

/** The world id (`wrld_…`) in [location], or null when there is none. */
fun worldIdOrNull(location: String?): String? =
    location?.substringBefore(":")?.takeIf { it.startsWith("wrld_") }

/** The world id in [location], or "" when there is none. */
fun parseWorldId(location: String): String = worldIdOrNull(location).orEmpty()

/** Follows a `traveling` presence to the destination it points at. */
fun resolvePresenceLocation(user: CurrentUser?): String = when (user?.location) {
    "traveling" -> user.travelingToLocation.orEmpty()
    else -> user?.location.orEmpty()
}

fun resolvePresenceLocation(friend: VrcUser?): String = when (friend?.location) {
    "traveling" -> friend.travelingToLocation.orEmpty()
    else -> friend?.location.orEmpty()
}

/** True when [location] points at a real, joinable instance. */
fun isTrackableLocation(location: String): Boolean =
    location.isNotBlank() &&
        location != "offline" &&
        location != "private" &&
        location != "traveling"

/** A `instance 12345` hint, or "" when [location] has no instance segment. */
fun formatInstanceHint(location: String): String {
    val instanceLabel = location.substringAfter(":", "").substringBefore("~")
    return if (instanceLabel.isBlank()) "" else "instance $instanceLabel"
}

/**
 * The world id a user is in — following a `traveling` presence to its
 * destination — or null when the resolved location is a sentinel/non-world.
 */
fun resolvedWorldId(location: String?, travelingToLocation: String?): String? =
    worldIdOrNull(if (location == "traveling") travelingToLocation else location)
