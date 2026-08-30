package com.vrcx.android.ui.screen.world

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.api.model.Instance
import com.vrcx.android.data.api.model.World
import com.vrcx.android.data.repository.WorldRepository
import com.vrcx.android.data.util.runCatchingCancellable
import com.vrcx.android.ui.common.LoadState
import com.vrcx.android.ui.common.completeLoad
import com.vrcx.android.ui.common.failLoad
import com.vrcx.android.ui.common.settleLoad
import com.vrcx.android.ui.common.startLoad
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A world and, when they could be fetched, the instances currently running it. */
data class WorldDetailData(val world: World, val instances: List<Instance> = emptyList())

@HiltViewModel
class WorldDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val worldRepository: WorldRepository,
) : ViewModel() {
    val worldId: String = savedStateHandle.get<String>("worldId").orEmpty()

    private val _state = MutableStateFlow<LoadState<WorldDetailData>>(LoadState.NotLoaded)
    val state: StateFlow<LoadState<WorldDetailData>> = _state.asStateFlow()

    /** The self-invite result, which is an action's outcome rather than a load's. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        loadWorld()
    }

    fun clearMessage() {
        _message.value = null
    }

    fun selfInvite(instanceId: String) {
        viewModelScope.launch {
            _message.value =
                runCatchingCancellable {
                    worldRepository.selfInvite(worldId, instanceId)
                    "Invite sent — check your VRChat notifications"
                }.getOrElse { failure -> "Self invite failed: ${failure.message}" }
        }
    }

    /**
     * Builds the canonical browser launch URL for an instance. Tapping it on a
     * device with VRChat installed opens the app via the OS handler; on devices
     * without VRChat it falls back to the website's launch page.
     *
     * The id charset VRChat uses today survives a query string untouched, so the
     * allow-list keeps the URL readable; anything outside it (`&`, `#`, `+`, a
     * space) would truncate the link instead of being carried through.
     */
    fun browserLaunchUrl(instanceId: String): String = "https://vrchat.com/home/launch" +
        "?worldId=${encodeQueryValue(worldId)}&instanceId=${encodeQueryValue(instanceId)}"

    private fun encodeQueryValue(value: String): String = Uri.encode(value, ":,")

    fun loadWorld() {
        viewModelScope.launch {
            _state.update { it.startLoad() }
            try {
                val worldResult =
                    runCatchingCancellable {
                        worldRepository.getWorld(worldId, forceRefresh = true)
                    }
                val world = worldResult.getOrNull()
                if (world == null) {
                    val failure = worldResult.exceptionOrNull()
                    _state.update { it.failLoad(failure?.message ?: "Failed to load world") }
                } else {
                    // The page is useful even when its ancillary instance fetch fails.
                    _state.update { it.completeLoad(WorldDetailData(world)) }
                    val instanceIds = worldRepository.parseInstanceIds(world)
                    if (instanceIds.isNotEmpty()) {
                        runCatchingCancellable {
                            worldRepository.getInstances(worldId, instanceIds)
                        }.onSuccess { instances ->
                            _state.update { it.completeLoad(WorldDetailData(world, instances)) }
                        }.onFailure { failure ->
                            _state.update {
                                it.failLoad(failure.message ?: "Failed to load instances")
                            }
                        }
                    }
                }
            } finally {
                _state.update { it.settleLoad() }
            }
        }
    }
}
