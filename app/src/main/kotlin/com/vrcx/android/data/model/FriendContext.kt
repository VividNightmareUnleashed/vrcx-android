package com.vrcx.android.data.model

import com.vrcx.android.data.api.model.VrcUser

enum class FriendState(val label: String) {
    ONLINE("Online"),
    ACTIVE("Active"),
    OFFLINE("Offline"),
}

/**
 * A friend as the app currently understands them. Whether they are favourited
 * or have presence notifications switched on is deliberately absent: both are
 * account-wide id sets that FriendRepository publishes separately, and stamping
 * them per friend meant re-deriving the whole map every time either set moved.
 */
data class FriendContext(
    val id: String,
    val name: String,
    val state: FriendState,
    val ref: VrcUser? = null,
)
