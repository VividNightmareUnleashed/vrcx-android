package com.vrcx.android.data.model

/**
 * A friend state change worth surfacing as a system notification.
 *
 * `FriendRepository` already resolves the friend's userId (tolerating the
 * lowercase `userid` pipeline-key variant) and display name, and computes the
 * offline/private filtering and location-change comparison while handling the
 * event. It emits these domain transitions so the foreground service can map
 * them straight to Android notifications instead of re-parsing the raw pipeline
 * payload — which previously read only `content["userId"]` and silently dropped
 * events that used the lowercase key.
 *
 * The service still applies the per-friend "notify enabled" gate.
 */
sealed interface FriendTransition {
    val userId: String
    val displayName: String

    /** The friend came online (from any pipeline online event for that friend). */
    data class CameOnline(
        override val userId: String,
        override val displayName: String,
    ) : FriendTransition

    /** The friend moved to a new, real world instance (not offline/private). */
    data class ChangedLocation(
        override val userId: String,
        override val displayName: String,
        val worldName: String,
    ) : FriendTransition

    /** The friend's online status changed (e.g. join-me / ask-me / busy). */
    data class ChangedStatus(
        override val userId: String,
        override val displayName: String,
        val status: String,
    ) : FriendTransition
}
