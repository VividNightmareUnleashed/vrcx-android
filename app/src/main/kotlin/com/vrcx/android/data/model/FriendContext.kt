package com.vrcx.android.data.model

import com.vrcx.android.data.api.model.VrcUser

enum class FriendState { ONLINE, ACTIVE, OFFLINE }

data class FriendContext(
    val id: String,
    val name: String,
    val state: FriendState,
    val ref: VrcUser? = null,
    val isVIP: Boolean = false,
    val pendingOffline: Boolean = false,
)
