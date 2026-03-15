package com.vrcx.android.ui.screen.world

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.api.model.Instance
import com.vrcx.android.data.api.model.World
import com.vrcx.android.data.repository.WorldRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WorldDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val worldRepository: WorldRepository,
) : ViewModel() {
    val worldId: String = savedStateHandle.get<String>("worldId") ?: ""

    private val _world = MutableStateFlow<World?>(null)
    val world: StateFlow<World?> = _world.asStateFlow()

    private val _instances = MutableStateFlow<List<Instance>>(emptyList())
    val instances: StateFlow<List<Instance>> = _instances.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init { loadWorld() }

    fun loadWorld() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val w = worldRepository.getWorld(worldId)
                _world.value = w
                // Load instances in background
                val instanceIds = worldRepository.parseInstanceIds(w)
                if (instanceIds.isNotEmpty()) {
                    _instances.value = worldRepository.getInstances(worldId, instanceIds)
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load world"
            } finally {
                _isLoading.value = false
            }
        }
    }
}
