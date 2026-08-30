package com.vrcx.android.ui.screen.groups

import com.vrcx.android.data.api.model.Group
import com.vrcx.android.data.api.model.GroupMember

internal object GroupMembershipPolicy {
    /** VRChat reports the status on `myMember` when a membership record exists. */
    fun status(group: Group?): GroupMembership {
        val rawStatus = group?.myMember?.membershipStatus?.takeIf(String::isNotBlank)
            ?: group?.membershipStatus.orEmpty()
        return when (rawStatus) {
            "member" -> GroupMembership.MEMBER
            "requested" -> GroupMembership.REQUESTED
            "invited" -> GroupMembership.INVITED
            else -> GroupMembership.UNKNOWN
        }
    }

    fun isMember(group: Group?): Boolean = status(group) == GroupMembership.MEMBER

    /** API permissions remain authoritative so regular members never see destructive controls. */
    fun canManageMembers(group: Group?): Boolean {
        val membership = group?.myMember ?: return false
        return isMember(group) &&
            (
                MANAGE_ALL_PERMISSION in membership.permissions ||
                    MANAGE_MEMBERS_PERMISSION in membership.permissions
                )
    }

    fun canRemoveMember(group: Group?, member: GroupMember): Boolean =
        canManageMembers(group) && hasRemovableIdentity(group, member)

    fun removableMemberUserIds(group: Group, members: List<GroupMember>): Set<String> {
        if (!canManageMembers(group)) return emptySet()
        return members.asSequence()
            .filter { member -> hasRemovableIdentity(group, member) }
            .mapTo(mutableSetOf()) { member -> member.userId }
    }

    private fun hasRemovableIdentity(group: Group?, member: GroupMember): Boolean = member.userId.isNotBlank() &&
        member.userId != group?.ownerId &&
        member.userId != group?.myMember?.userId
}

private const val MANAGE_ALL_PERMISSION = "*"
private const val MANAGE_MEMBERS_PERMISSION = "group-members-manage"
