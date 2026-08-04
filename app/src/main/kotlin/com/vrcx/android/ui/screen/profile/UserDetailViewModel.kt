package com.vrcx.android.ui.screen.profile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.api.model.Avatar
import com.vrcx.android.data.api.model.Group
import com.vrcx.android.data.api.model.VrcUser
import com.vrcx.android.data.api.model.World
import com.vrcx.android.data.repository.FavoriteWorldLoadResult
import com.vrcx.android.data.repository.FavoriteWorldSection
import com.vrcx.android.data.repository.UserActionPerformer
import com.vrcx.android.data.repository.UserDetailRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class UserDetailTab(val label: String) {
    INFO("Info"),
    MUTUALS("Mutuals"),
    GROUPS("Groups"),
    WORLDS("Worlds"),
    AVATARS("Avatars"),
    FAVORITE_WORLDS("Fav Worlds"),
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
    val isFavorited: Boolean = false,
    val favoriteEntryId: String? = null,
    val memo: String? = null,
    val note: String? = null,
    val notifyEnabled: Boolean = false,
    val loadingTabs: Set<UserDetailTab> = emptySet(),
    val loadedTabs: Set<UserDetailTab> = emptySet(),
    val isSelf: Boolean = false,
)

@HiltViewModel
class UserDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val userDetailRepository: UserDetailRepository,
    private val actionPerformer: UserActionPerformer,
) : ViewModel() {
    val userId: String = savedStateHandle.get<String>("userId") ?: ""

    private val _uiState = MutableStateFlow(UserDetailUiState())
    val uiState: StateFlow<UserDetailUiState> = _uiState.asStateFlow()

    private var profileJob: Job? = null
    private var profileGeneration = 0L
    private var actionJob: Job? = null

    init {
        observeSelfStatus()
        observeFavoriteStatus()
        loadFavoriteStatus()
        loadUser()
    }

    fun loadUser() {
        val generation = ++profileGeneration
        profileJob?.cancel()
        profileJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val profile = userDetailRepository.loadProfile(userId)
                if (generation != profileGeneration) return@launch
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
                    try {
                        userDetailRepository.cacheProfilePicture(profile.user)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {}
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == profileGeneration) {
                    _uiState.update { it.copy(message = "Failed to load user: ${e.message}") }
                }
            } finally {
                if (generation == profileGeneration) {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    fun selectTab(tab: UserDetailTab) {
        when (tab) {
            UserDetailTab.INFO -> Unit
            UserDetailTab.MUTUALS -> loadTab(
                tab = tab,
                failureMessage = "Failed to load mutual friends",
                load = { userDetailRepository.loadMutualFriends(userId) },
                apply = { state, users -> state.copy(mutualFriends = users) },
            )
            UserDetailTab.GROUPS -> loadTab(
                tab = tab,
                failureMessage = "Failed to load groups",
                load = { userDetailRepository.loadGroups(userId) },
                apply = { state, groups -> state.copy(userGroups = groups) },
            )
            UserDetailTab.WORLDS -> loadTab(
                tab = tab,
                failureMessage = "Failed to load worlds",
                load = { userDetailRepository.loadWorlds(userId) },
                apply = { state, worlds -> state.copy(userWorlds = worlds) },
            )
            UserDetailTab.AVATARS -> loadTab(
                tab = tab,
                failureMessage = "Failed to load avatars",
                load = { userDetailRepository.loadAvatars(userId) },
                apply = { state, avatars -> state.copy(userAvatars = avatars) },
            )
            UserDetailTab.FAVORITE_WORLDS -> loadTab(
                tab = tab,
                failureMessage = "Failed to load favorite worlds",
                load = { userDetailRepository.loadFavoriteWorlds(userId) },
                apply = ::applyFavoriteWorlds,
            )
        }
        _uiState.update { it.copy(selectedTab = tab) }
    }

    private fun <T> loadTab(
        tab: UserDetailTab,
        failureMessage: String,
        load: suspend () -> T,
        apply: (UserDetailUiState, T) -> UserDetailUiState,
    ) {
        val state = _uiState.value
        if (tab in state.loadedTabs || tab in state.loadingTabs) return
        _uiState.update { it.copy(loadingTabs = it.loadingTabs + tab) }
        viewModelScope.launch {
            try {
                val result = load()
                _uiState.update { state ->
                    val updated = apply(state, result)
                    updated.copy(
                        loadingTabs = updated.loadingTabs - tab,
                        loadedTabs = updated.loadedTabs + tab,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        loadingTabs = it.loadingTabs - tab,
                        message = "$failureMessage: ${e.message}",
                    )
                }
            }
        }
    }

    private fun applyFavoriteWorlds(
        state: UserDetailUiState,
        result: FavoriteWorldLoadResult,
    ): UserDetailUiState {
        val selectedTag = state.selectedFavoriteWorldTag?.takeIf { selected ->
            result.sections.any { it.tag == selected }
        } ?: result.sections.firstOrNull()?.tag
        return state.copy(
            favoriteWorldSections = result.sections,
            selectedFavoriteWorldTag = selectedTag,
            message = result.warning ?: state.message,
        )
    }

    fun selectFavoriteWorldGroup(tag: String) {
        _uiState.update { it.copy(selectedFavoriteWorldTag = tag) }
    }

    fun toggleFavorite() {
        viewModelScope.launch {
            val state = _uiState.value
            try {
                if (state.isFavorited && state.favoriteEntryId != null) {
                    userDetailRepository.deleteFavorite(state.favoriteEntryId)
                    showMessage("Removed from favorites")
                } else {
                    userDetailRepository.addFriendFavorite(userId)
                    showMessage("Added to favorites")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showMessage("Failed: ${e.message}")
            }
        }
    }

    private fun observeFavoriteStatus() {
        viewModelScope.launch {
            userDetailRepository.favorites.collect { favorites ->
                val favorite = favorites.firstOrNull {
                    it.type == "friend" && it.favoriteId == userId
                }
                _uiState.update {
                    it.copy(
                        favoriteEntryId = favorite?.id,
                        isFavorited = favorite != null,
                    )
                }
            }
        }
    }

    private fun observeSelfStatus() {
        viewModelScope.launch {
            userDetailRepository.observeIsSelf(userId).collect { isSelf ->
                _uiState.update { it.copy(isSelf = isSelf) }
            }
        }
    }

    private fun loadFavoriteStatus() {
        viewModelScope.launch {
            try {
                userDetailRepository.loadFavoriteStatus()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Favorite metadata is optional for the rest of the profile.
            }
        }
    }

    fun saveNote(text: String) {
        viewModelScope.launch {
            try {
                val user = _uiState.value.user
                if (!userDetailRepository.saveNote(userId, user?.displayName.orEmpty(), text)) return@launch
                _uiState.update {
                    it.copy(
                        note = text,
                        user = it.user?.copy(note = text),
                        message = if (text.isBlank()) "Note cleared" else "Note saved",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showMessage("Failed: ${e.message}")
            }
        }
    }

    fun saveMemo(text: String) {
        viewModelScope.launch {
            try {
                if (!userDetailRepository.saveMemo(userId, text)) return@launch
                _uiState.update { it.copy(memo = text, message = "Memo saved") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showMessage("Failed: ${e.message}")
            }
        }
    }

    fun toggleNotify() {
        viewModelScope.launch {
            try {
                val enabled = userDetailRepository.toggleNotify(userId)
                _uiState.update {
                    it.copy(
                        notifyEnabled = enabled,
                        message = if (enabled) "Notifications enabled" else "Notifications disabled",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showMessage("Failed: ${e.message}")
            }
        }
    }

    fun requestInvite() = runAction("Invite requested") { actionPerformer.requestInvite(userId) }

    fun sendInvite() = runAction("Invite sent") { actionPerformer.sendInvite(userId) }

    fun sendBoop() = runAction("Boop sent") { actionPerformer.sendBoop(userId) }

    fun sendFriendRequest() = runAction("Friend request sent", refreshProfile = true) {
        actionPerformer.sendFriendRequest(userId)
    }

    fun cancelFriendRequest() = runAction("Friend request cancelled", refreshProfile = true) {
        actionPerformer.cancelFriendRequest(userId)
    }

    fun unfriend() = runAction("Unfriended", refreshProfile = true) {
        actionPerformer.unfriend(userId)
    }

    fun blockUser() = runAction("User blocked") { actionPerformer.block(userId) }

    fun muteUser() = runAction("User muted") { actionPerformer.mute(userId) }

    fun hideAvatar() = runAction("Avatar hidden") { actionPerformer.hideAvatar(userId) }

    fun showAvatar() = runAction("Avatar shown") { actionPerformer.showAvatar(userId) }

    /**
     * Runs one profile write action. Only one runs at a time — several of these
     * are plain buttons with no confirm dialog, and a double tap would otherwise
     * send the request twice.
     */
    private fun runAction(
        successMessage: String,
        refreshProfile: Boolean = false,
        perform: suspend () -> Unit,
    ) {
        if (actionJob?.isActive == true) return
        actionJob = viewModelScope.launch {
            try {
                perform()
                showMessage(successMessage)
                if (refreshProfile) loadUser()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showMessage("Failed: ${e.message}")
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private fun showMessage(message: String) {
        _uiState.update { it.copy(message = message) }
    }
}
