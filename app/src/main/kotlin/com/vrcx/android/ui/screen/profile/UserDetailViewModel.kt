package com.vrcx.android.ui.screen.profile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.api.model.Avatar
import com.vrcx.android.data.api.model.Group
import com.vrcx.android.data.api.model.VrcUser
import com.vrcx.android.data.api.model.World
import com.vrcx.android.data.repository.FavoriteRepository
import com.vrcx.android.data.repository.FavoriteWorldSection
import com.vrcx.android.data.repository.ProfilePreferenceActions
import com.vrcx.android.data.repository.UserActionPerformer
import com.vrcx.android.data.repository.UserDetailRepository
import com.vrcx.android.data.util.runCatchingCancellable
import com.vrcx.android.data.util.runIgnoringFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class UserDetailTab(val label: String, internal val failureMessage: String) {
    INFO("Info", "Failed to load user info"),
    MUTUALS("Mutuals", "Failed to load mutual friends"),
    GROUPS("Groups", "Failed to load groups"),
    WORLDS("Worlds", "Failed to load worlds"),
    AVATARS("Avatars", "Failed to load avatars"),
    FAVORITE_WORLDS("Fav Worlds", "Failed to load favorite worlds"),
}

data class UserDetailUiState(
    val user: VrcUser? = null,
    val isLoading: Boolean = true,
    val message: String? = null,
    val selectedTab: UserDetailTab = UserDetailTab.INFO,
    val mutualFriends: List<VrcUser> = emptyList(),
    val userGroups: List<Group> = emptyList(),
    val userWorlds: List<World> = emptyList(),
    val userAvatars: List<Avatar> = emptyList(),
    val favoriteWorldSections: List<FavoriteWorldSection> = emptyList(),
    val selectedFavoriteWorldTag: String? = null,
    val favoriteEntryId: String? = null,
    val memo: String? = null,
    val note: String? = null,
    val notifyEnabled: Boolean = false,
    val loadingTabs: Set<UserDetailTab> = emptySet(),
    val loadedTabs: Set<UserDetailTab> = emptySet(),
    val isSelf: Boolean = false,
) {
    /** The favorite entry is the single source of truth for this derived flag. */
    val isFavorited: Boolean get() = favoriteEntryId != null
}

@HiltViewModel
class UserDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val userDetailRepository: UserDetailRepository,
    actionPerformer: UserActionPerformer,
    profilePreferenceActions: ProfilePreferenceActions,
    private val favoriteRepository: FavoriteRepository,
) : ViewModel() {
    val userId: String = savedStateHandle.get<String>("userId").orEmpty()

    private val _uiState = MutableStateFlow(UserDetailUiState())
    val uiState: StateFlow<UserDetailUiState> = _uiState.asStateFlow()

    private val tabLoader = UserDetailTabLoader(userId, userDetailRepository)
    private val mutationRunner = UserDetailMutationRunner(userId, actionPerformer, profilePreferenceActions)
    private var profileJob: Job? = null
    private var profileGeneration = 0L
    private var serializedMutationInProgress = false

    init {
        observeState()
        loadUser()
    }

    internal fun onIntent(intent: UserDetailIntent) {
        when (intent) {
            UserDetailIntent.Reload -> loadUser()

            is UserDetailIntent.SelectTab -> selectTab(intent.tab)

            is UserDetailIntent.SelectFavoriteWorldGroup -> {
                _uiState.update { it.copy(selectedFavoriteWorldTag = intent.tag) }
            }

            is UserDetailIntent.Mutate -> mutate(intent.mutation)

            UserDetailIntent.ClearMessage -> _uiState.update { it.copy(message = null) }
        }
    }

    private fun loadUser() {
        val generation = ++profileGeneration
        profileJob?.cancel()
        profileJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = runCatchingCancellable { userDetailRepository.loadProfile(userId) }
            if (generation != profileGeneration) return@launch

            result.onSuccess { profile ->
                _uiState.update { state ->
                    state.copy(
                        user = profile.user,
                        note = profile.note,
                        memo = profile.memo,
                        notifyEnabled = profile.notifyEnabled,
                        message = profile.localDataError?.let { "Failed to load user: $it" } ?: state.message,
                    )
                }
                viewModelScope.launch {
                    runIgnoringFailure { userDetailRepository.cacheProfilePicture(profile.user) }
                }
            }.onFailure { failure ->
                _uiState.update { it.copy(message = "Failed to load user: ${failure.message}") }
            }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    private fun selectTab(tab: UserDetailTab) {
        if (tab != UserDetailTab.INFO) loadTab(tab)
        _uiState.update { it.copy(selectedTab = tab) }
    }

    private fun loadTab(tab: UserDetailTab) {
        val state = _uiState.value
        if (tab in state.loadedTabs || tab in state.loadingTabs) return
        _uiState.update { it.copy(loadingTabs = it.loadingTabs + tab) }
        viewModelScope.launch {
            runCatchingCancellable { tabLoader.load(tab) }
                .onSuccess { result ->
                    _uiState.update { current ->
                        val updated = result.applyTo(current)
                        updated.copy(
                            loadingTabs = updated.loadingTabs - tab,
                            loadedTabs = updated.loadedTabs + tab,
                        )
                    }
                }
                .onFailure { failure ->
                    _uiState.update {
                        it.copy(
                            loadingTabs = it.loadingTabs - tab,
                            message = "${tab.failureMessage}: ${failure.message}",
                        )
                    }
                }
        }
    }

    private fun observeState() {
        viewModelScope.launch {
            favoriteRepository.favorites.collect { favorites ->
                val favorite = favorites.firstOrNull { it.type == "friend" && it.favoriteId == userId }
                _uiState.update { it.copy(favoriteEntryId = favorite?.id) }
            }
        }
        viewModelScope.launch {
            userDetailRepository.observeIsSelf(userId).collect { isSelf ->
                _uiState.update { it.copy(isSelf = isSelf) }
            }
        }
        viewModelScope.launch {
            runIgnoringFailure { favoriteRepository.loadFavorites(type = "friend") }
        }
    }

    private fun mutate(mutation: UserDetailMutation) {
        // Favoriting and social actions are tap-driven remote writes, so only one may run at a time.
        val serialized = mutation.requiresSerialization
        if (serialized && serializedMutationInProgress) return
        if (serialized) serializedMutationInProgress = true
        val state = _uiState.value
        viewModelScope.launch {
            try {
                runCatchingCancellable { mutationRunner.execute(mutation, state) }
                    .onSuccess { result -> result?.let(::applyMutation) }
                    .onFailure { failure -> _uiState.update { it.copy(message = "Failed: ${failure.message}") } }
            } finally {
                if (serialized) serializedMutationInProgress = false
            }
        }
    }

    private fun applyMutation(result: UserDetailMutationResult) {
        _uiState.update { state ->
            state.copy(
                user = result.note?.let { state.user?.copy(note = it) } ?: state.user,
                note = result.note ?: state.note,
                memo = result.memo ?: state.memo,
                notifyEnabled = result.notifyEnabled ?: state.notifyEnabled,
                message = result.message,
            )
        }
        if (result.refreshProfile) {
            invalidateTabs()
            loadUser()
        }
    }

    private fun invalidateTabs() {
        // Relationship changes can revoke access to every lazily loaded profile tab.
        val selected = _uiState.value.selectedTab
        _uiState.update {
            it.copy(
                mutualFriends = emptyList(),
                userGroups = emptyList(),
                userWorlds = emptyList(),
                userAvatars = emptyList(),
                favoriteWorldSections = emptyList(),
                loadedTabs = emptySet(),
            )
        }
        selectTab(selected)
    }
}
