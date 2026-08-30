package com.vrcx.android.ui.screen.moderation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.api.model.PlayerModeration
import com.vrcx.android.data.repository.ModerationRepository
import com.vrcx.android.di.DefaultDispatcher
import com.vrcx.android.ui.common.LoadState
import com.vrcx.android.ui.common.completeLoad
import com.vrcx.android.ui.common.failLoad
import com.vrcx.android.ui.common.isBusy
import com.vrcx.android.ui.common.settleLoad
import com.vrcx.android.ui.common.startLoad
import com.vrcx.android.ui.common.whileUiSubscribed
import com.vrcx.android.ui.components.ConfirmDialog
import com.vrcx.android.ui.components.EmptyState
import com.vrcx.android.ui.components.ErrorState
import com.vrcx.android.ui.components.LoadingState
import com.vrcx.android.ui.components.VrcxDetailTopBar
import com.vrcx.android.ui.components.VrcxScrollableTabRow
import com.vrcx.android.ui.components.VrcxSearchBar
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
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

val MODERATION_TABS = listOf(
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
    /**
     * [isMutating] is deliberately outside [load]: removing a moderation is an
     * action over data already on screen, not a load of it.
     */
    data class ScreenState(val load: LoadState<Unit> = LoadState.NotLoaded, val isMutating: Boolean = false) {
        /** The one home for a message the screen shows in its snackbar. */
        val staleError: String? get() = (load as? LoadState.Loaded)?.staleError
    }

    /** The visible rows and the per-tab counts, derived together so they cannot disagree. */
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

    val moderations: StateFlow<ModerationList> = combine(
        moderationRepository.moderations,
        _selectedTab,
        _searchQuery,
    ) { mods, tab, query ->
        ModerationList(
            visible = mods.filter {
                it.type == tab.type &&
                    (query.isBlank() || it.targetDisplayName.contains(query, ignoreCase = true))
            },
            countsByType = mods.groupingBy { it.type }.eachCount(),
        )
    }
        .flowOn(defaultDispatcher)
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
                moderationRepository.deleteModeration(moderation)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportFailure(e.message ?: "Failed to remove moderation")
            } finally {
                _screenState.update { it.copy(isMutating = false) }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _screenState.update { it.copy(load = it.load.startLoad()) }
            try {
                moderationRepository.loadModerations()
                _screenState.update { it.copy(load = it.load.completeLoad(Unit)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _screenState.update {
                    it.copy(load = it.load.failLoad(e.message ?: "Failed to load moderations"))
                }
            } finally {
                _screenState.update { it.copy(load = it.load.settleLoad()) }
            }
        }
    }

    fun consumeError() {
        _screenState.update { it.copy(load = it.load.clearStaleError()) }
    }

    /**
     * A mutation failure rides along with the rows it left on screen, so it
     * reaches the snackbar through the same field a failed refresh does.
     */
    private fun reportFailure(message: String) {
        _screenState.update { state ->
            state.copy(load = (state.load as? LoadState.Loaded)?.copy(staleError = message) ?: state.load)
        }
    }

    private fun LoadState<Unit>.clearStaleError(): LoadState<Unit> =
        (this as? LoadState.Loaded)?.copy(staleError = null) ?: this
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModerationScreen(viewModel: ModerationViewModel = hiltViewModel(), onBack: () -> Unit = {}) {
    val moderations by viewModel.moderations.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val screenState by viewModel.screenState.collectAsStateWithLifecycle()
    var pendingRemove by remember { mutableStateOf<PlayerModeration?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(screenState.staleError) {
        screenState.staleError?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.consumeError()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            VrcxDetailTopBar(
                title = "Moderation",
                onBack = onBack,
                actions = {
                    IconButton(onClick = { viewModel.refresh() }, enabled = !screenState.load.isBusy) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                    }
                },
            )

            VrcxScrollableTabRow(selectedTabIndex = MODERATION_TABS.indexOf(selectedTab)) {
                MODERATION_TABS.forEach { tab ->
                    val count = moderations.countsByType[tab.type] ?: 0
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        text = {
                            Text(if (count > 0) "${tab.label} ($count)" else tab.label)
                        },
                    )
                }
            }

            VrcxSearchBar(
                query = searchQuery,
                onQueryChange = { viewModel.updateSearch(it) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )

            when (val load = screenState.load) {
                LoadState.NotLoaded, LoadState.Loading -> LoadingState()

                is LoadState.Failed -> ErrorState(message = load.message, onRetry = viewModel::refresh)

                else -> if (moderations.visible.isEmpty()) {
                    EmptyState(message = "No ${selectedTab.label.lowercase()} users", icon = Icons.Outlined.Block)
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(moderations.visible, key = { it.id }) { mod ->
                            Row(
                                Modifier.fillMaxWidth().padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        mod.targetDisplayName,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    Text(
                                        mod.created.take(10),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                OutlinedButton(
                                    onClick = { pendingRemove = mod },
                                    enabled = !screenState.isMutating,
                                ) {
                                    Text("Remove")
                                }
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }

    // Confirmation dialog
    pendingRemove?.let { moderation ->
        ConfirmDialog(
            title = "Remove Moderation",
            message = "Remove ${selectedTab.label.lowercase()} moderation for ${moderation.targetDisplayName}?",
            confirmLabel = "Remove",
            onConfirm = {
                viewModel.remove(moderation)
                pendingRemove = null
            },
            onDismiss = { pendingRemove = null },
        )
    }
}
