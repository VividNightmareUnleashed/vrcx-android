package com.vrcx.android.ui.screen.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.api.model.Avatar
import com.vrcx.android.data.api.model.Favorite
import com.vrcx.android.data.api.model.FavoriteGroup
import com.vrcx.android.data.api.model.World
import com.vrcx.android.data.api.model.displayAvatarUrl
import com.vrcx.android.data.model.FriendContext
import com.vrcx.android.data.model.FriendState
import com.vrcx.android.data.repository.FavoriteRepository
import com.vrcx.android.data.repository.FriendRepository
import com.vrcx.android.data.repository.UserRepository
import com.vrcx.android.data.util.captureFailure
import com.vrcx.android.ui.common.LoadState
import com.vrcx.android.ui.common.completeLoad
import com.vrcx.android.ui.common.failLoad
import com.vrcx.android.ui.common.isBusy
import com.vrcx.android.ui.common.isLoaded
import com.vrcx.android.ui.common.settleLoad
import com.vrcx.android.ui.common.startLoad
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import javax.inject.Inject

enum class FavoritesTab(
    val label: String,
    val favoriteTypes: Set<String>,
) {
    FRIENDS("Friends", setOf("friend")),
    WORLDS("Worlds", setOf("world", "vrcPlusWorld")),
    AVATARS("Avatars", setOf("avatar")),
}

data class FavoritesUiState(
    val selectedTab: FavoritesTab = FavoritesTab.FRIENDS,
    /** Each tab's rows live in [FavoriteRepository], so the state carries no value. */
    val tabs: Map<FavoritesTab, LoadState<Unit>> =
        FavoritesTab.entries.associateWith { LoadState.NotLoaded },
) {
    val selectedTabState: LoadState<Unit> get() = tabs.getValue(selectedTab)
}

data class ResolvedFavorite(
    val favorite: Favorite,
    val name: String,
    val thumbnailUrl: String = "",
    val subtitle: String = "",
    val groupTags: List<String> = emptyList(),
    val friendState: FriendState? = null,
    val friendStatus: String? = null,
)

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val favoriteRepository: FavoriteRepository,
    private val friendRepository: FriendRepository,
    private val userRepository: UserRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(FavoritesUiState())
    val uiState: StateFlow<FavoritesUiState> = _uiState.asStateFlow()

    private val _resolvedFavorites = MutableStateFlow<List<ResolvedFavorite>>(emptyList())
    val resolvedFavorites: StateFlow<List<ResolvedFavorite>> = _resolvedFavorites.asStateFlow()

    val favoriteGroups: StateFlow<List<FavoriteGroup>> = favoriteRepository.favoriteGroups

    init {
        viewModelScope.launch { collectAndResolve() }
        loadTab(FavoritesTab.FRIENDS)
    }

    fun selectTab(tab: FavoritesTab) {
        loadTab(tab)
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun refresh() = loadTab(_uiState.value.selectedTab, forceRefresh = true)

    fun retry() = refresh()

    private fun loadTab(tab: FavoritesTab, forceRefresh: Boolean = false) {
        val current = _uiState.value.tabs.getValue(tab)
        if (current.isBusy || (!forceRefresh && current.isLoaded)) return

        updateTab(tab) { it.startLoad() }
        viewModelScope.launch {
            try {
                val outcome = when (tab) {
                    FavoritesTab.FRIENDS -> loadFriends(forceRefresh)
                    FavoritesTab.WORLDS -> loadWorlds(forceRefresh)
                    FavoritesTab.AVATARS -> loadAvatars(forceRefresh)
                }
                updateTab(tab) { state ->
                    if (outcome.hasFavoriteData) {
                        state.completeLoad(
                            value = Unit,
                            warning = "Some ${tab.label.lowercase()} details could not be loaded."
                                .takeIf { outcome.hasPartialFailure },
                        )
                    } else {
                        state.failLoad("Failed to load ${tab.label.lowercase()} favorites")
                    }
                }
            } finally {
                // Runs on cancellation too — otherwise the tab stays busy and
                // this method's own guard blocks every later retry.
                updateTab(tab) { it.settleLoad() }
            }
        }
    }

    private suspend fun loadFriends(forceRefresh: Boolean): LoadOutcome = supervisorScope {
        val favorites = async {
            captureFailure {
                favoriteRepository.loadFavorites(type = "friend", forceRefresh = forceRefresh)
            }
        }
        val groups = async {
            captureFailure { favoriteRepository.loadFavoriteGroups(forceRefresh = forceRefresh) }
        }
        val favoriteFailure = favorites.await()
        val groupFailure = groups.await()
        LoadOutcome(
            hasFavoriteData = favoriteFailure == null,
            hasPartialFailure = groupFailure != null,
        )
    }

    private suspend fun loadWorlds(forceRefresh: Boolean): LoadOutcome = supervisorScope {
        val worlds = async {
            captureFailure {
                favoriteRepository.loadFavorites(type = "world", forceRefresh = forceRefresh)
            }
        }
        val vrcPlusWorlds = async {
            captureFailure {
                favoriteRepository.loadFavorites(type = "vrcPlusWorld", forceRefresh = forceRefresh)
            }
        }
        val groups = async {
            captureFailure { favoriteRepository.loadFavoriteGroups(forceRefresh = forceRefresh) }
        }
        val details = async {
            captureFailure { favoriteRepository.loadFavoriteWorldsBulk(forceRefresh = forceRefresh) }
        }
        val favoriteFailures = listOf(worlds.await(), vrcPlusWorlds.await())
        val optionalFailures = listOf(groups.await(), details.await())
        LoadOutcome(
            hasFavoriteData = favoriteFailures.any { it == null },
            hasPartialFailure = (favoriteFailures + optionalFailures).any { it != null },
        )
    }

    private suspend fun loadAvatars(forceRefresh: Boolean): LoadOutcome = supervisorScope {
        val favorites = async {
            captureFailure {
                favoriteRepository.loadFavorites(type = "avatar", forceRefresh = forceRefresh)
            }
        }
        val groups = async {
            captureFailure { favoriteRepository.loadFavoriteGroups(forceRefresh = forceRefresh) }
        }
        val details = async {
            captureFailure { favoriteRepository.loadFavoriteAvatarsBulk(forceRefresh = forceRefresh) }
        }
        val favoriteFailure = favorites.await()
        val optionalFailures = listOf(groups.await(), details.await())
        LoadOutcome(
            hasFavoriteData = favoriteFailure == null,
            hasPartialFailure = optionalFailures.any { it != null },
        )
    }

    private fun updateTab(tab: FavoritesTab, transform: (LoadState<Unit>) -> LoadState<Unit>) {
        _uiState.update { state ->
            state.copy(tabs = state.tabs + (tab to transform(state.tabs.getValue(tab))))
        }
    }

    private suspend fun collectAndResolve() {
        combine(
            favoriteRepository.favorites,
            favoriteRepository.favoriteWorlds,
            favoriteRepository.favoriteAvatars,
            friendRepository.friends,
        ) { favorites, worlds, avatars, friends ->
            ResolutionInputs(favorites, worlds, avatars, friends)
        }.collectLatest { inputs ->
            resolveEntities(inputs)
        }
    }

    private suspend fun resolveEntities(inputs: ResolutionInputs) {
        val worldsById = inputs.worlds.associateBy { it.id }
        val avatarsById = inputs.avatars.associateBy { it.id }
        _resolvedFavorites.value = inputs.favorites.map { favorite ->
            try {
                when (favorite.type) {
                    "friend" -> resolveFriend(favorite, inputs.friends[favorite.favoriteId])
                    "world", "vrcPlusWorld" -> resolveWorld(favorite, worldsById[favorite.favoriteId])
                    "avatar" -> resolveAvatar(favorite, avatarsById[favorite.favoriteId])
                    else -> favorite.asUnresolved()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                favorite.asUnresolved()
            }
        }
    }

    private suspend fun resolveFriend(
        favorite: Favorite,
        friend: FriendContext?,
    ): ResolvedFavorite {
        if (friend != null) {
            return ResolvedFavorite(
                favorite = favorite,
                name = friend.name,
                thumbnailUrl = friend.ref?.displayAvatarUrl().orEmpty(),
                subtitle = friend.ref?.statusDescription.orEmpty(),
                groupTags = favorite.tags,
                friendState = friend.state,
                friendStatus = friend.ref?.status,
            )
        }
        val user = userRepository.getUser(favorite.favoriteId)
        return ResolvedFavorite(
            favorite = favorite,
            name = user.displayName,
            thumbnailUrl = user.displayAvatarUrl(),
            subtitle = user.statusDescription,
            groupTags = favorite.tags,
        )
    }

    private fun resolveWorld(favorite: Favorite, world: World?): ResolvedFavorite =
        world?.let {
            ResolvedFavorite(
                favorite = favorite,
                name = it.name,
                thumbnailUrl = it.thumbnailImageUrl,
                subtitle = it.authorName,
                groupTags = favorite.tags,
            )
        } ?: favorite.asUnresolved()

    private fun resolveAvatar(favorite: Favorite, avatar: Avatar?): ResolvedFavorite =
        avatar?.let {
            ResolvedFavorite(
                favorite = favorite,
                name = it.name,
                thumbnailUrl = it.thumbnailImageUrl,
                subtitle = "by ${it.authorName}",
                groupTags = favorite.tags,
            )
        } ?: favorite.asUnresolved()

    private fun Favorite.asUnresolved() = ResolvedFavorite(
        favorite = this,
        name = favoriteId,
        groupTags = tags,
    )

    fun unfavorite(favoriteId: String) {
        val tab = _uiState.value.selectedTab
        viewModelScope.launch {
            try {
                favoriteRepository.deleteFavorite(favoriteId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A rejected delete is a warning over data that is still on
                // screen, not a reason to blank the tab.
                updateTab(tab) { state ->
                    (state as? LoadState.Loaded)
                        ?.copy(warning = e.message ?: "Failed to remove favorite")
                        ?: state
                }
            }
        }
    }

    private data class LoadOutcome(
        val hasFavoriteData: Boolean,
        val hasPartialFailure: Boolean,
    )

    private data class ResolutionInputs(
        val favorites: List<Favorite>,
        val worlds: List<World>,
        val avatars: List<Avatar>,
        val friends: Map<String, FriendContext>,
    )
}
