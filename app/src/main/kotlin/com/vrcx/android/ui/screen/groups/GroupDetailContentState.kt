package com.vrcx.android.ui.screen.groups

import com.vrcx.android.data.api.model.Group

internal data class GroupDetailContentState(
    val group: Group,
    val detail: GroupDetailState,
    val membershipStatus: GroupMembership,
    val isActionLoading: Boolean,
    val removableMemberUserIds: Set<String>,
)
