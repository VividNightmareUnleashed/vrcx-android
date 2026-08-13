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

enum class GroupTab(val label: String) {
    MEMBERS("Members"),
    INSTANCES("Instances"),
    POSTS("Posts"),
}

/** The membership values the VRChat group endpoints return, parsed once at the boundary. */
enum class GroupMembership {
    MEMBER,
    REQUESTED,
    INVITED,
    UNKNOWN,
}

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

    fun onTabSelected(tab: GroupTab) {
        when (tab) {
            GroupTab.MEMBERS -> Unit
            GroupTab.INSTANCES -> if (_state.value.instances == GroupResourceState.NotLoaded) loadInstances()
            GroupTab.POSTS -> if (_state.value.posts == GroupResourceState.NotLoaded) loadPosts(reset = true)
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
        val paging = membersPaging()
        if (reset) loadFirstPage(paging) else appendNextPage(paging)
    }

    private fun loadPosts(reset: Boolean) {
        val paging = postsPaging()
        if (reset) loadFirstPage(paging) else appendNextPage(paging)
    }

    private fun membersPaging() = PagedResource(
        current = { _state.value.members },
        update = { members -> _state.update { it.copy(members = members) } },
        fallbackError = "Failed to load members",
        fetch = { offset -> groupRepository.getGroupMembersPage(groupId, offset = offset) },
        total = { _, itemCount -> currentGroup()?.memberCount?.takeIf { it >= itemCount } },
        key = { member -> member.id.ifBlank { member.userId } },
    )

    private fun postsPaging() = PagedResource(
        current = { _state.value.posts },
        update = { posts -> _state.update { it.copy(posts = posts) } },
        fallbackError = "Failed to load posts",
        fetch = { offset -> groupRepository.getGroupPostsPage(groupId, offset = offset) },
        total = { page, _ -> page.total },
        key = GroupPost::id,
    )

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

    /** Everything `loadFirstPage` and `appendNextPage` need to drive one paged field of the state. */
    private class PagedResource<T>(
        val current: () -> GroupResourceState<GroupPagedData<T>>,
        val update: (GroupResourceState<GroupPagedData<T>>) -> Unit,
        val fallbackError: String,
        val fetch: suspend (offset: Int) -> GroupPage<T>,
        val total: (page: GroupPage<T>, itemCount: Int) -> Int?,
        val key: (T) -> String,
    )

    private fun <T> loadFirstPage(resource: PagedResource<T>) {
        if (resource.current() == GroupResourceState.Loading) return
        resource.update(GroupResourceState.Loading)

        viewModelScope.launch {
            try {
                val page = resource.fetch(0)
                val items = page.items.distinctBy(resource.key)
                resource.update(
                    GroupResourceState.Ready(
                        pagedData(page, items, resource.total(page, items.size), page.nextOffset),
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                resource.update(GroupResourceState.Error(e.message ?: resource.fallbackError))
            }
        }
    }

    private fun <T> appendNextPage(resource: PagedResource<T>) {
        val initial = resource.current()
        val previous = (initial as? GroupResourceState.Ready)?.value
        if (initial == GroupResourceState.Loading ||
            previous == null ||
            !previous.hasMore ||
            previous.appendState == GroupAppendState.Loading
        ) {
            return
        }

        resource.update(GroupResourceState.Ready(previous.copy(appendState = GroupAppendState.Loading)))

        viewModelScope.launch {
            // A concurrent mutation (a kick, a reset) can move the page under us,
            // so read the live value back before merging and carry the offset shift.
            fun latest(): GroupPagedData<T> =
                (resource.current() as? GroupResourceState.Ready)?.value ?: previous
            try {
                val page = resource.fetch(previous.nextOffset)
                val latest = latest()
                val items = (latest.items + page.items).distinctBy(resource.key)
                val authoritativeTotal = resource.total(page, items.size) ?: latest.totalCount
                val nextOffset = (page.nextOffset + (latest.nextOffset - previous.nextOffset))
                    .coerceAtLeast(0)
                resource.update(
                    GroupResourceState.Ready(
                        pagedData(page, items, authoritativeTotal, nextOffset),
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message ?: resource.fallbackError
                resource.update(
                    GroupResourceState.Ready(latest().copy(appendState = GroupAppendState.Error(message))),
                )
            }
        }
    }

    private fun <T> pagedData(
        page: GroupPage<T>,
        items: List<T>,
        authoritativeTotal: Int?,
        nextOffset: Int,
    ) = GroupPagedData(
        items = items,
        nextOffset = nextOffset,
        totalCount = authoritativeTotal,
        hasMore = page.items.isNotEmpty() &&
            (authoritativeTotal?.let { nextOffset < it } ?: page.hasMore),
    )

    private fun applyAuthoritativeMemberCount(count: Int) {
        _state.update { state ->
            state.copy(
                members = state.members.mapReady { members ->
                    if (count < members.items.size) {
                        members
                    } else {
                        members.copy(
                            totalCount = count,
                            hasMore = members.items.isNotEmpty() && members.nextOffset < count,
                        )
                    }
                },
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
                    membershipStatus(updated) == GroupMembership.REQUESTED -> "Join request sent"
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

    fun membershipStatus(group: Group?): GroupMembership {
        // VRChat puts the status on myMember when the caller has a membership
        // record, and on the group itself otherwise.
        val raw = group?.myMember?.membershipStatus?.takeIf { it.isNotBlank() }
            ?: group?.membershipStatus.orEmpty()
        return when (raw) {
            "member" -> GroupMembership.MEMBER
            "requested" -> GroupMembership.REQUESTED
            "invited" -> GroupMembership.INVITED
            else -> GroupMembership.UNKNOWN
        }
    }

    fun isMember(group: Group?): Boolean = membershipStatus(group) == GroupMembership.MEMBER

    private fun currentGroup(): Group? =
        (_state.value.group as? GroupResourceState.Ready)?.value

    private fun updateGroup(transform: (Group) -> Group) {
        _state.update { it.copy(group = it.group.mapReady(transform)) }
    }

    private fun removeMemberFromState(userId: String) {
        val current = (_state.value.members as? GroupResourceState.Ready)?.value ?: return
        if (current.items.none { it.userId == userId }) return
        _state.update { state ->
            state.copy(
                members = state.members.mapReady { members ->
                    members.copy(
                        items = members.items.filterNot { it.userId == userId },
                        nextOffset = (members.nextOffset - 1).coerceAtLeast(0),
                        totalCount = members.totalCount?.let { total -> (total - 1).coerceAtLeast(0) },
                    )
                },
            )
        }
    }
}

/** Transforms the value inside a Ready resource, leaving every other case untouched. */
private fun <T> GroupResourceState<T>.mapReady(transform: (T) -> T): GroupResourceState<T> =
    if (this is GroupResourceState.Ready) GroupResourceState.Ready(transform(value)) else this
