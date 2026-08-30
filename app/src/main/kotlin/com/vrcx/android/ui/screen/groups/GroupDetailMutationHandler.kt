package com.vrcx.android.ui.screen.groups

import com.vrcx.android.data.api.model.Group
import com.vrcx.android.data.repository.GroupRepository
import com.vrcx.android.data.util.runCatchingCancellable
import com.vrcx.android.ui.common.LoadState
import com.vrcx.android.ui.common.completeLoad
import com.vrcx.android.ui.common.isBusy
import com.vrcx.android.ui.common.valueOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class GroupDetailMutationHandler(
    private val groupId: String,
    private val groupRepository: GroupRepository,
    private val state: MutableStateFlow<GroupDetailState>,
    private val scope: CoroutineScope,
) {
    fun dispatch(intent: GroupDetailIntent.Mutation) {
        when (intent) {
            GroupDetailIntent.JoinOrLeaveGroup -> joinOrLeaveGroup()
            is GroupDetailIntent.KickMember -> kickMember(intent.userId)
            is GroupDetailIntent.ClearMessage -> clearMessage(intent.source)
        }
    }

    private fun kickMember(userId: String) {
        val current = state.value
        if (
            current.isActionLoading ||
            current.members.isBusy ||
            current.members.valueOrNull?.appendState == GroupAppendState.Loading
        ) {
            return
        }
        state.update { it.copy(isActionLoading = true, removingMemberUserId = userId) }
        scope.launch {
            try {
                runCatchingCancellable { groupRepository.kickGroupMember(groupId, userId) }
                    .fold(
                        onSuccess = { removed -> publishKickResult(userId, removed) },
                        onFailure = { failure ->
                            state.update {
                                it.copy(message = failure.message ?: "Failed to remove member")
                            }
                        },
                    )
            } finally {
                state.update {
                    it.copy(isActionLoading = false, removingMemberUserId = null)
                }
            }
        }
    }

    private fun publishKickResult(userId: String, removed: Boolean) {
        if (!removed) {
            state.update {
                it.copy(message = "Failed to remove member (insufficient permissions?)")
            }
            return
        }
        removeMemberFromState(userId)
        updateGroup { group ->
            group.copy(memberCount = (group.memberCount - 1).coerceAtLeast(0))
        }
        state.update { it.copy(message = "Member removed") }
    }

    private fun joinOrLeaveGroup() {
        val group = state.value.group.valueOrNull ?: return
        if (state.value.isActionLoading) return
        state.update { it.copy(isActionLoading = true, removingMemberUserId = null) }
        scope.launch {
            try {
                val wasMember = GroupMembershipPolicy.isMember(group)
                runCatchingCancellable {
                    if (wasMember) {
                        groupRepository.leaveGroup(group.id)
                    } else {
                        groupRepository.joinGroup(group.id)
                    }
                }.fold(
                    onSuccess = { updated -> publishMembershipChange(updated, wasMember) },
                    onFailure = { failure ->
                        state.update {
                            it.copy(message = failure.message ?: "Group action failed")
                        }
                    },
                )
            } finally {
                state.update { it.copy(isActionLoading = false) }
            }
        }
    }

    private fun publishMembershipChange(group: Group, wasMember: Boolean) {
        state.update {
            it.copy(
                group = it.group.completeLoad(group),
                message = when {
                    wasMember -> "Left group"

                    GroupMembershipPolicy.status(group) == GroupMembership.REQUESTED ->
                        "Join request sent"

                    else -> "Joined group"
                },
            )
        }
    }

    private fun clearMessage(source: GroupDetailMessageSource) {
        state.update { current ->
            when (source) {
                GroupDetailMessageSource.ACTION -> current.copy(message = null)

                GroupDetailMessageSource.GROUP ->
                    current.copy(group = current.group.clearStaleError())

                GroupDetailMessageSource.MEMBERS ->
                    current.copy(members = current.members.clearStaleError())

                GroupDetailMessageSource.INSTANCES ->
                    current.copy(instances = current.instances.clearStaleError())

                GroupDetailMessageSource.POSTS ->
                    current.copy(posts = current.posts.clearStaleError())
            }
        }
    }

    private fun updateGroup(transform: (Group) -> Group) {
        state.update { it.copy(group = it.group.mapLoaded(transform)) }
    }

    private fun removeMemberFromState(userId: String) {
        val current = state.value.members.valueOrNull ?: return
        if (current.items.none { it.userId == userId }) return
        state.update { detail ->
            detail.copy(
                members = detail.members.mapLoaded { members ->
                    members.copy(
                        items = members.items.filterNot { it.userId == userId },
                        nextOffset = (members.nextOffset - 1).coerceAtLeast(0),
                        totalCount = members.totalCount?.let { total ->
                            (total - 1).coerceAtLeast(0)
                        },
                    )
                },
            )
        }
    }
}

private fun <T> LoadState<T>.clearStaleError(): LoadState<T> =
    if (this is LoadState.Loaded) copy(staleError = null) else this
