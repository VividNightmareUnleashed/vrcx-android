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
import com.vrcx.android.ui.common.LoadState
import com.vrcx.android.ui.common.completeLoad
import com.vrcx.android.ui.common.failLoad
import com.vrcx.android.ui.common.isBusy
import com.vrcx.android.ui.common.settleLoad
import com.vrcx.android.ui.common.startLoad
import com.vrcx.android.ui.common.valueOrNull
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

enum class GroupDetailMessageSource {
    ACTION,
    GROUP,
    MEMBERS,
    INSTANCES,
    POSTS,
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
    val group: LoadState<Group> = LoadState.NotLoaded,
    val members: LoadState<GroupPagedData<GroupMember>> = LoadState.NotLoaded,
    val instances: LoadState<List<GroupInstance>> = LoadState.NotLoaded,
    val posts: LoadState<GroupPagedData<GroupPost>> = LoadState.NotLoaded,
    val selectedTab: GroupTab = GroupTab.MEMBERS,
    val isActionLoading: Boolean = false,
    val removingMemberUserId: String? = null,
    val message: String? = null,
)

@HiltViewModel
class GroupDetailViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val groupRepository: GroupRepository,
) : ViewModel() {
    private val groupId: String = savedStateHandle.get<String>("groupId").orEmpty()

    private val _state = MutableStateFlow(
        GroupDetailState(
            selectedTab = savedStateHandle.get<String>(SELECTED_TAB_KEY)
                ?.let { saved -> GroupTab.entries.firstOrNull { it.name == saved } }
                ?: GroupTab.MEMBERS,
        ),
    )
    val state: StateFlow<GroupDetailState> = _state.asStateFlow()

    init {
        loadGroup()
        loadMembers(reset = true)
        when (_state.value.selectedTab) {
            GroupTab.MEMBERS -> Unit
            GroupTab.INSTANCES -> loadInstances()
            GroupTab.POSTS -> loadPosts(reset = true)
        }
    }

    fun retryGroup() = loadGroup()

    fun retryMembers() {
        if (_state.value.isActionLoading) return
        loadMembers(reset = true)
    }

    fun retryInstances() = loadInstances()

    fun retryPosts() = loadPosts(reset = true)

    fun loadMoreMembers() {
        if (_state.value.isActionLoading) return
        loadMembers(reset = false)
    }

    fun loadMorePosts() = loadPosts(reset = false)

    fun onTabSelected(tab: GroupTab) {
        savedStateHandle[SELECTED_TAB_KEY] = tab.name
        _state.update { it.copy(selectedTab = tab) }
        when (tab) {
            GroupTab.MEMBERS -> Unit
            GroupTab.INSTANCES -> if (_state.value.instances == LoadState.NotLoaded) loadInstances()
            GroupTab.POSTS -> if (_state.value.posts == LoadState.NotLoaded) loadPosts(reset = true)
        }
    }

    private fun loadGroup() {
        loadResource(
            current = { _state.value.group },
            update = { group -> _state.update { it.copy(group = group) } },
            fallbackError = "Failed to load group",
            fetch = { groupRepository.getGroup(groupId) },
            onLoaded = { applyAuthoritativeMemberCount(it.memberCount) },
        )
    }

    private fun loadInstances() {
        loadResource(
            current = { _state.value.instances },
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
        current: () -> LoadState<T>,
        update: (LoadState<T>) -> Unit,
        fallbackError: String,
        fetch: suspend () -> T,
        onLoaded: (T) -> Unit = {
        },
    ) {
        if (current().isBusy) return
        update(current().startLoad())
        viewModelScope.launch {
            try {
                val value = fetch()
                update(current().completeLoad(value))
                onLoaded(value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update(current().failLoad(e.message ?: fallbackError))
            } finally {
                update(current().settleLoad())
            }
        }
    }

    /** Everything `loadFirstPage` and `appendNextPage` need to drive one paged field of the state. */
    private class PagedResource<T>(
        val current: () -> LoadState<GroupPagedData<T>>,
        val update: (LoadState<GroupPagedData<T>>) -> Unit,
        val fallbackError: String,
        val fetch: suspend (offset: Int) -> GroupPage<T>,
        val total: (page: GroupPage<T>, itemCount: Int) -> Int?,
        val key: (T) -> String,
    )

    private fun <T> loadFirstPage(resource: PagedResource<T>) {
        if (resource.current().isBusy) return
        resource.update(resource.current().startLoad())

        viewModelScope.launch {
            try {
                val page = resource.fetch(0)
                val items = page.items.distinctBy(resource.key)
                resource.update(
                    resource.current().completeLoad(
                        pagedData(page, items, resource.total(page, items.size), page.nextOffset),
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                resource.update(resource.current().failLoad(e.message ?: resource.fallbackError))
            } finally {
                resource.update(resource.current().settleLoad())
            }
        }
    }

    private fun <T> appendNextPage(resource: PagedResource<T>) {
        val initial = resource.current()
        val previous = initial.valueOrNull
        if (initial.isBusy || previous == null) return
        if (!previous.hasMore || previous.appendState == GroupAppendState.Loading) return

        resource.update(initial.completeLoad(previous.copy(appendState = GroupAppendState.Loading)))

        viewModelScope.launch {
            // A concurrent refresh can move the page under us,
            // so read the live value back before merging and carry the offset shift.
            fun latest(): GroupPagedData<T> = resource.current().valueOrNull ?: previous
            try {
                val page = resource.fetch(previous.nextOffset)
                val latest = latest()
                val items = (latest.items + page.items).distinctBy(resource.key)
                val authoritativeTotal = resource.total(page, items.size) ?: latest.totalCount
                val nextOffset = (page.nextOffset + (latest.nextOffset - previous.nextOffset))
                    .coerceAtLeast(0)
                resource.update(
                    resource.current().completeLoad(
                        pagedData(page, items, authoritativeTotal, nextOffset),
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message ?: resource.fallbackError
                resource.update(
                    resource.current().completeLoad(
                        latest().copy(appendState = GroupAppendState.Error(message)),
                    ),
                )
            }
        }
    }

    private fun <T> pagedData(page: GroupPage<T>, items: List<T>, authoritativeTotal: Int?, nextOffset: Int) =
        GroupPagedData(
            items = items,
            nextOffset = nextOffset,
            totalCount = authoritativeTotal,
            hasMore = page.items.isNotEmpty() &&
                (authoritativeTotal?.let { nextOffset < it } ?: page.hasMore),
        )

    private fun applyAuthoritativeMemberCount(count: Int) {
        _state.update { state ->
            state.copy(
                members = state.members.mapLoaded { members ->
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
        val currentState = _state.value
        if (currentState.isActionLoading || currentState.members.isBusy ||
            currentState.members.valueOrNull?.appendState == GroupAppendState.Loading
        ) {
            return
        }
        _state.update { it.copy(isActionLoading = true, removingMemberUserId = userId) }
        viewModelScope.launch {
            try {
                if (groupRepository.kickGroupMember(groupId, userId)) {
                    removeMemberFromState(userId)
                    updateGroup { it.copy(memberCount = (it.memberCount - 1).coerceAtLeast(0)) }
                    _state.update { it.copy(message = "Member removed") }
                } else {
                    _state.update {
                        it.copy(message = "Failed to remove member (insufficient permissions?)")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = e.message ?: "Failed to remove member") }
            } finally {
                _state.update { it.copy(isActionLoading = false, removingMemberUserId = null) }
            }
        }
    }

    fun canRemoveMember(group: Group?, member: GroupMember): Boolean =
        canManageMembers(group) && member.userId.isNotBlank() &&
            member.userId != group?.ownerId && member.userId != group?.myMember?.userId

    fun joinOrLeaveGroup() {
        val currentGroup = currentGroup() ?: return
        if (_state.value.isActionLoading) return
        _state.update { it.copy(isActionLoading = true, removingMemberUserId = null) }
        viewModelScope.launch {
            try {
                val wasMember = isMember(currentGroup)
                val updated = if (wasMember) {
                    groupRepository.leaveGroup(currentGroup.id)
                } else {
                    groupRepository.joinGroup(currentGroup.id)
                }
                _state.update {
                    it.copy(
                        group = it.group.completeLoad(updated),
                        message = when {
                            wasMember -> "Left group"

                            membershipStatus(
                                updated,
                            ) == GroupMembership.REQUESTED -> "Join request sent"

                            else -> "Joined group"
                        },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = e.message ?: "Group action failed") }
            } finally {
                _state.update { it.copy(isActionLoading = false) }
            }
        }
    }

    fun clearMessage(source: GroupDetailMessageSource) {
        _state.update { state ->
            when (source) {
                GroupDetailMessageSource.ACTION -> state.copy(message = null)
                GroupDetailMessageSource.GROUP -> state.copy(group = state.group.clearStaleError())
                GroupDetailMessageSource.MEMBERS -> state.copy(members = state.members.clearStaleError())
                GroupDetailMessageSource.INSTANCES -> state.copy(instances = state.instances.clearStaleError())
                GroupDetailMessageSource.POSTS -> state.copy(posts = state.posts.clearStaleError())
            }
        }
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

    private fun currentGroup(): Group? = _state.value.group.valueOrNull

    private fun updateGroup(transform: (Group) -> Group) {
        _state.update { it.copy(group = it.group.mapLoaded(transform)) }
    }

    private fun removeMemberFromState(userId: String) {
        val current = _state.value.members.valueOrNull ?: return
        if (current.items.none { it.userId == userId }) return
        _state.update { state ->
            state.copy(
                members = state.members.mapLoaded { members ->
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

private fun <T> LoadState<T>.mapLoaded(transform: (T) -> T): LoadState<T> =
    if (this is LoadState.Loaded) copy(value = transform(value)) else this

private fun <T> LoadState<T>.clearStaleError(): LoadState<T> =
    if (this is LoadState.Loaded) copy(staleError = null) else this

private const val SELECTED_TAB_KEY = "selectedGroupTab"
