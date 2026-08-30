package com.vrcx.android.ui.screen.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.api.model.Favorite
import com.vrcx.android.data.api.model.FavoriteGroup
import com.vrcx.android.data.model.FriendState
import com.vrcx.android.data.repository.FavoriteRepository
import com.vrcx.android.data.repository.FriendRepository
import com.vrcx.android.data.repository.UserRepository
import com.vrcx.android.data.util.runCatchingCancellable
import com.vrcx.android.ui.common.LoadState
import com.vrcx.android.ui.common.completeLoad
import com.vrcx.android.ui.common.failLoad
import com.vrcx.android.ui.common.isBusy
import com.vrcx.android.ui.common.isLoaded
import com.vrcx.android.ui.common.settleLoad
import com.vrcx.android.ui.common.startLoad
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class FavoritesTab(val label: String, val favoriteTypes: Set<String>) {
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
    private val tabLoader = FavoriteTabLoader(favoriteRepository)
    private val resolver = FavoriteResolver(userRepository)

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
                val outcome = tabLoader.load(tab, forceRefresh)
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
            FavoriteResolutionInputs(favorites, worlds, avatars, friends)
        }.collectLatest { inputs ->
            _resolvedFavorites.value = resolver.resolve(inputs)
        }
    }

    fun unfavorite(favoriteId: String) {
        val tab = _uiState.value.selectedTab
        viewModelScope.launch {
            runCatchingCancellable {
                favoriteRepository.deleteFavorite(favoriteId)
            }.exceptionOrNull()?.let { failure ->
                updateTab(tab) { state ->
                    (state as? LoadState.Loaded)
                        ?.copy(warning = failure.message ?: "Failed to remove favorite")
                        ?: state
                }
            }
        }
    }
}
