package com.vrcx.android.ui.screen.moderation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.api.model.PlayerModeration
import com.vrcx.android.data.repository.ModerationRepository
import com.vrcx.android.data.util.runCatchingCancellable
import com.vrcx.android.di.DefaultDispatcher
import com.vrcx.android.ui.common.LoadState
import com.vrcx.android.ui.common.completeLoad
import com.vrcx.android.ui.common.failLoad
import com.vrcx.android.ui.common.settleLoad
import com.vrcx.android.ui.common.startLoad
import com.vrcx.android.ui.common.whileUiSubscribed
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ModerationTab(val type: String, val label: String)

val MODERATION_TABS =
    listOf(
        ModerationTab("block", "Blocked"),
        ModerationTab("mute", "Muted"),
        ModerationTab("hideAvatar", "Hide Avatar"),
        ModerationTab("showAvatar", "Show Avatar"),
        ModerationTab("interactOff", "Interact Off"),
        ModerationTab("interactOn", "Interact On"),
    )

@HiltViewModel
class ModerationViewModel @Inject constructor(
    private val moderationRepository: ModerationRepository,
    @DefaultDispatcher defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {
    data class ScreenState(val load: LoadState<Unit> = LoadState.NotLoaded, val isMutating: Boolean = false) {
        val staleError: String?
            get() = (load as? LoadState.Loaded)?.staleError
    }

    data class ModerationList(
        val visible: List<PlayerModeration> = emptyList(),
        val countsByType: Map<String, Int> = emptyMap(),
    )

    private val _screenState = MutableStateFlow(ScreenState())
    val screenState: StateFlow<ScreenState> = _screenState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedTab = MutableStateFlow(MODERATION_TABS.first())
    val selectedTab: StateFlow<ModerationTab> = _selectedTab.asStateFlow()

    val moderations: StateFlow<ModerationList> =
        combine(
            moderationRepository.moderations,
            _selectedTab,
            _searchQuery,
        ) { moderations, tab, query ->
            ModerationList(
                visible =
                    moderations.filter {
                        it.type == tab.type &&
                            (
                                query.isBlank() ||
                                    it.targetDisplayName.contains(query, ignoreCase = true)
                                )
                    },
                countsByType = moderations.groupingBy { it.type }.eachCount(),
            )
        }.flowOn(defaultDispatcher)
            .stateIn(viewModelScope, whileUiSubscribed, ModerationList())

    init {
        refresh()
    }

    fun selectTab(tab: ModerationTab) {
        _selectedTab.value = tab
    }

    fun updateSearch(query: String) {
        _searchQuery.value = query
    }

    fun remove(moderation: PlayerModeration) {
        viewModelScope.launch {
            _screenState.update { it.copy(isMutating = true, load = it.load.clearStaleError()) }
            try {
                runCatchingCancellable { moderationRepository.deleteModeration(moderation) }
                    .exceptionOrNull()
                    ?.let { reportFailure(it.message ?: "Failed to remove moderation") }
            } finally {
                _screenState.update { it.copy(isMutating = false) }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _screenState.update { it.copy(load = it.load.startLoad()) }
            try {
                runCatchingCancellable { moderationRepository.loadModerations() }
                    .fold(
                        onSuccess = { _ ->
                            _screenState.update { state ->
                                state.copy(load = state.load.completeLoad(Unit))
                            }
                        },
                        onFailure = { failure ->
                            _screenState.update { state ->
                                state.copy(
                                    load =
                                        state.load.failLoad(
                                            failure.message ?: "Failed to load moderations",
                                        ),
                                )
                            }
                        },
                    )
            } finally {
                _screenState.update { it.copy(load = it.load.settleLoad()) }
            }
        }
    }

    fun consumeError() {
        _screenState.update { it.copy(load = it.load.clearStaleError()) }
    }

    private fun reportFailure(message: String) {
        _screenState.update { state ->
            state.copy(
                load =
                    (state.load as? LoadState.Loaded)?.copy(staleError = message)
                        ?: state.load,
            )
        }
    }

    private fun LoadState<Unit>.clearStaleError(): LoadState<Unit> =
        (this as? LoadState.Loaded)?.copy(staleError = null) ?: this
}
