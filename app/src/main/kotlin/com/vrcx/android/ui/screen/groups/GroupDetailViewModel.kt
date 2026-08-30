package com.vrcx.android.ui.screen.groups

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.api.model.Group
import com.vrcx.android.data.api.model.GroupInstance
import com.vrcx.android.data.api.model.GroupMember
import com.vrcx.android.data.api.model.GroupPost
import com.vrcx.android.data.repository.GroupRepository
import com.vrcx.android.ui.common.LoadState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class GroupTab(val label: String) {
    MEMBERS("Members"),
    INSTANCES("Instances"),
    POSTS("Posts"),
}

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

internal sealed interface GroupDetailIntent {
    sealed interface Load : GroupDetailIntent

    data object RetryGroup : Load
    data object RetryMembers : Load
    data object RetryInstances : Load
    data object RetryPosts : Load
    data object LoadMoreMembers : Load
    data object LoadMorePosts : Load

    sealed interface Mutation : GroupDetailIntent

    data object JoinOrLeaveGroup : Mutation
    data class KickMember(val userId: String) : Mutation
    data class ClearMessage(val source: GroupDetailMessageSource) : Mutation

    data class SelectTab(val tab: GroupTab) : GroupDetailIntent
}

@HiltViewModel
class GroupDetailViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    groupRepository: GroupRepository,
) : ViewModel() {
    private val groupId = savedStateHandle.get<String>(GROUP_ID_KEY).orEmpty()
    private val _state = MutableStateFlow(
        GroupDetailState(selectedTab = savedStateHandle.selectedGroupTab()),
    )
    val state: StateFlow<GroupDetailState> = _state.asStateFlow()

    private val dataLoader = GroupDetailDataLoader(
        groupId = groupId,
        groupRepository = groupRepository,
        state = _state,
        scope = viewModelScope,
    )
    private val mutationHandler = GroupDetailMutationHandler(
        groupId = groupId,
        groupRepository = groupRepository,
        state = _state,
        scope = viewModelScope,
    )

    init {
        dataLoader.dispatch(GroupDetailIntent.RetryGroup)
        dataLoader.dispatch(GroupDetailIntent.RetryMembers)
        when (_state.value.selectedTab) {
            GroupTab.MEMBERS -> Unit
            GroupTab.INSTANCES -> dataLoader.dispatch(GroupDetailIntent.RetryInstances)
            GroupTab.POSTS -> dataLoader.dispatch(GroupDetailIntent.RetryPosts)
        }
    }

    internal fun dispatch(intent: GroupDetailIntent) {
        when (intent) {
            is GroupDetailIntent.Load -> dataLoader.dispatch(intent)
            is GroupDetailIntent.Mutation -> mutationHandler.dispatch(intent)
            is GroupDetailIntent.SelectTab -> selectTab(intent.tab)
        }
    }

    private fun selectTab(tab: GroupTab) {
        savedStateHandle[SELECTED_TAB_KEY] = tab.name
        _state.update { it.copy(selectedTab = tab) }
        when (tab) {
            GroupTab.MEMBERS -> Unit

            GroupTab.INSTANCES -> if (_state.value.instances == LoadState.NotLoaded) {
                dataLoader.dispatch(GroupDetailIntent.RetryInstances)
            }

            GroupTab.POSTS -> if (_state.value.posts == LoadState.NotLoaded) {
                dataLoader.dispatch(GroupDetailIntent.RetryPosts)
            }
        }
    }
}

private fun SavedStateHandle.selectedGroupTab(): GroupTab = get<String>(SELECTED_TAB_KEY)
    ?.let { saved -> GroupTab.entries.firstOrNull { it.name == saved } }
    ?: GroupTab.MEMBERS

private const val GROUP_ID_KEY = "groupId"
private const val SELECTED_TAB_KEY = "selectedGroupTab"
