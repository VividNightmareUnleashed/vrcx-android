package com.vrcx.android.ui.screen.groups

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.api.model.Group
import com.vrcx.android.data.api.model.GroupInstance
import com.vrcx.android.data.api.model.GroupMember
import com.vrcx.android.data.api.model.GroupPost
import com.vrcx.android.data.repository.GroupPage
import com.vrcx.android.data.repository.GroupRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface GroupResourceState<out T> {
    data object NotLoaded : GroupResourceState<Nothing>
    data object Loading : GroupResourceState<Nothing>
    data class Ready<T>(val value: T) : GroupResourceState<T>
    data class Error(val message: String) : GroupResourceState<Nothing>
}

sealed interface GroupAppendState {
    data object Idle : GroupAppendState
    data object Loading : GroupAppendState
    data class Error(val message: String) : GroupAppendState
}

data class GroupPagedData<T>(
    val items: List<T>,
    val nextOffset: Int,
    val totalCount: Int? = null,
    val hasMore: Boolean = false,
    val appendState: GroupAppendState = GroupAppendState.Idle,
)

data class GroupDetailState(
    val group: GroupResourceState<Group> = GroupResourceState.NotLoaded,
    val members: GroupResourceState<GroupPagedData<GroupMember>> = GroupResourceState.NotLoaded,
    val instances: GroupResourceState<List<GroupInstance>> = GroupResourceState.NotLoaded,
    val posts: GroupResourceState<GroupPagedData<GroupPost>> = GroupResourceState.NotLoaded,
)

@HiltViewModel
class GroupDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val groupRepository: GroupRepository,
) : ViewModel() {
    private val groupId: String = savedStateHandle.get<String>("groupId") ?: ""

    private val _state = MutableStateFlow(GroupDetailState())
    val state: StateFlow<GroupDetailState> = _state.asStateFlow()

    private val _isActionLoading = MutableStateFlow(false)
    val isActionLoading: StateFlow<Boolean> = _isActionLoading.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        loadGroup()
        loadMembers(reset = true)
    }

    fun retryGroup() = loadGroup()

    fun retryMembers() = loadMembers(reset = true)

    fun retryInstances() = loadInstances()

    fun retryPosts() = loadPosts(reset = true)

    fun loadMoreMembers() = loadMembers(reset = false)

    fun loadMorePosts() = loadPosts(reset = false)

    fun onTabSelected(index: Int) {
        when (index) {
            INSTANCES_TAB -> if (_state.value.instances == GroupResourceState.NotLoaded) loadInstances()
            POSTS_TAB -> if (_state.value.posts == GroupResourceState.NotLoaded) loadPosts(reset = true)
        }
    }

    private fun loadGroup() {
        loadResource(
            current = _state.value.group,
            update = { group -> _state.update { it.copy(group = group) } },
            fallbackError = "Failed to load group",
            fetch = { groupRepository.getGroup(groupId) },
            onLoaded = { applyAuthoritativeMemberCount(it.memberCount) },
        )
    }

    private fun loadInstances() {
        loadResource(
            current = _state.value.instances,
            update = { instances -> _state.update { it.copy(instances = instances) } },
            fallbackError = "Failed to load instances",
            fetch = { groupRepository.getGroupInstances(groupId) },
        )
    }

    private fun loadMembers(reset: Boolean) {
        loadPage(
            reset = reset,
            current = { _state.value.members },
            update = { members -> _state.update { it.copy(members = members) } },
            fallbackError = "Failed to load members",
            fetch = { offset -> groupRepository.getGroupMembersPage(groupId, offset = offset) },
            total = { _, itemCount ->
                currentGroup()?.memberCount?.takeIf { it >= itemCount }
            },
            key = { member -> member.id.ifBlank { member.userId } },
        )
    }

    private fun loadPosts(reset: Boolean) {
        loadPage(
            reset = reset,
            current = { _state.value.posts },
            update = { posts -> _state.update { it.copy(posts = posts) } },
            fallbackError = "Failed to load posts",
            fetch = { offset -> groupRepository.getGroupPostsPage(groupId, offset = offset) },
            total = { page, _ -> page.total },
            key = GroupPost::id,
        )
    }

    private fun <T> loadResource(
        current: GroupResourceState<T>,
        update: (GroupResourceState<T>) -> Unit,
        fallbackError: String,
        fetch: suspend () -> T,
        onLoaded: (T) -> Unit = {},
    ) {
        if (current == GroupResourceState.Loading) return
        update(GroupResourceState.Loading)
        viewModelScope.launch {
            try {
                val value = fetch()
                update(GroupResourceState.Ready(value))
                onLoaded(value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update(GroupResourceState.Error(e.message ?: fallbackError))
            }
        }
    }

    private fun <T> loadPage(
        reset: Boolean,
        current: () -> GroupResourceState<GroupPagedData<T>>,
        update: (GroupResourceState<GroupPagedData<T>>) -> Unit,
        fallbackError: String,
        fetch: suspend (offset: Int) -> GroupPage<T>,
        total: (page: GroupPage<T>, itemCount: Int) -> Int?,
        key: (T) -> String,
    ) {
        val initial = current()
        val previous = (initial as? GroupResourceState.Ready)?.value
        if (initial == GroupResourceState.Loading ||
            (!reset && (previous == null || !previous.hasMore || previous.appendState == GroupAppendState.Loading))
        ) {
            return
        }

        update(
            if (reset) {
                GroupResourceState.Loading
            } else {
                GroupResourceState.Ready(previous!!.copy(appendState = GroupAppendState.Loading))
            },
        )

        viewModelScope.launch {
            try {
                val offset = if (reset) 0 else previous!!.nextOffset
                val page = fetch(offset)
                val latest = if (reset) null else {
                    (current() as? GroupResourceState.Ready)?.value ?: previous
                }
                val items = (if (reset) page.items else latest?.items.orEmpty() + page.items)
                    .distinctBy(key)
                val authoritativeTotal = total(page, items.size)
                    ?: if (reset) null else latest?.totalCount
                val nextOffset = if (reset) {
                    page.nextOffset
                } else {
                    val concurrentOffsetChange = latest!!.nextOffset - previous!!.nextOffset
                    (page.nextOffset + concurrentOffsetChange).coerceAtLeast(0)
                }
                update(
                    GroupResourceState.Ready(
                        GroupPagedData(
                            items = items,
                            nextOffset = nextOffset,
                            totalCount = authoritativeTotal,
                            hasMore = page.items.isNotEmpty() &&
                                (authoritativeTotal?.let { nextOffset < it } ?: page.hasMore),
                        ),
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message ?: fallbackError
                val latest = if (reset) null else {
                    (current() as? GroupResourceState.Ready)?.value ?: previous
                }
                update(
                    if (reset || previous == null) {
                        GroupResourceState.Error(message)
                    } else {
                        GroupResourceState.Ready(
                            latest!!.copy(appendState = GroupAppendState.Error(message)),
                        )
                    },
                )
            }
        }
    }

    private fun applyAuthoritativeMemberCount(count: Int) {
        val current = (_state.value.members as? GroupResourceState.Ready)?.value ?: return
        if (count < current.items.size) return
        _state.update {
            it.copy(
                members = GroupResourceState.Ready(
                    current.copy(
                        totalCount = count,
                        hasMore = current.items.isNotEmpty() && current.nextOffset < count,
                    ),
                ),
            )
        }
    }

    /** The API remains authoritative; this keeps destructive controls hidden from regular members. */
    fun canManageMembers(group: Group?): Boolean {
        val myMember = group?.myMember ?: return false
        if (!isMember(group)) return false
        return "*" in myMember.permissions || "group-members-manage" in myMember.permissions
    }

    fun kickMember(userId: String) {
        if (_isActionLoading.value) return
        _isActionLoading.value = true
        viewModelScope.launch {
            try {
                if (groupRepository.kickGroupMember(groupId, userId)) {
                    removeMemberFromState(userId)
                    updateGroup { it.copy(memberCount = (it.memberCount - 1).coerceAtLeast(0)) }
                    _message.value = "Member removed"
                } else {
                    _message.value = "Failed to remove member (insufficient permissions?)"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = e.message ?: "Failed to remove member"
            } finally {
                _isActionLoading.value = false
            }
        }
    }

    fun canRemoveMember(group: Group?, member: GroupMember): Boolean =
        canManageMembers(group) && member.userId.isNotBlank() &&
            member.userId != group?.ownerId && member.userId != group?.myMember?.userId

    fun joinOrLeaveGroup() {
        val currentGroup = currentGroup() ?: return
        if (_isActionLoading.value) return
        _isActionLoading.value = true
        viewModelScope.launch {
            try {
                val wasMember = isMember(currentGroup)
                val updated = if (wasMember) {
                    groupRepository.leaveGroup(currentGroup.id)
                } else {
                    groupRepository.joinGroup(currentGroup.id)
                }
                _state.update { it.copy(group = GroupResourceState.Ready(updated)) }
                _message.value = when {
                    wasMember -> "Left group"
                    membershipStatus(updated) == "requested" -> "Join request sent"
                    else -> "Joined group"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = e.message ?: "Group action failed"
            } finally {
                _isActionLoading.value = false
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    fun membershipStatus(group: Group?): String =
        group?.myMember?.membershipStatus?.takeIf { it.isNotBlank() }
            ?: group?.membershipStatus.orEmpty()

    fun isMember(group: Group?): Boolean = membershipStatus(group) == "member"

    private fun currentGroup(): Group? =
        (_state.value.group as? GroupResourceState.Ready)?.value

    private fun updateGroup(transform: (Group) -> Group) {
        val current = currentGroup() ?: return
        _state.update { it.copy(group = GroupResourceState.Ready(transform(current))) }
    }

    private fun removeMemberFromState(userId: String) {
        val current = (_state.value.members as? GroupResourceState.Ready)?.value ?: return
        val filtered = current.items.filterNot { it.userId == userId }
        if (filtered.size == current.items.size) return
        _state.update {
            it.copy(
                members = GroupResourceState.Ready(
                    current.copy(
                        items = filtered,
                        nextOffset = (current.nextOffset - 1).coerceAtLeast(0),
                        totalCount = current.totalCount?.let { total -> (total - 1).coerceAtLeast(0) },
                    ),
                ),
            )
        }
    }

    private companion object {
        const val INSTANCES_TAB = 1
        const val POSTS_TAB = 2
    }
}
