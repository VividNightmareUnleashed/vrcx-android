package com.vrcx.android.data.model

/**
 * A normalized friend state change eligible for a system notification.
 *
 * Identity, display name, confirmation delays, and location filtering have
 * already been resolved. Consumers apply delivery preferences without
 * reinterpreting the raw pipeline payload.
 */
sealed interface FriendTransition {
    val userId: String
    val displayName: String

    /** The friend came online (from any pipeline online event for that friend). */
    data class CameOnline(override val userId: String, override val displayName: String) : FriendTransition

    /** The friend's offline event remained valid through the confirmation delay. */
    data class CameOffline(override val userId: String, override val displayName: String) : FriendTransition

    /** The friend moved to a new, real world instance (not offline/private). */
    data class ChangedLocation(override val userId: String, override val displayName: String, val worldName: String) :
        FriendTransition

    /** The friend's online status changed (e.g. join-me / ask-me / busy). */
    data class ChangedStatus(override val userId: String, override val displayName: String, val status: String) :
        FriendTransition
}
