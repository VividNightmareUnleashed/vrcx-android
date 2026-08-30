package com.vrcx.android.ui.screen.avatars

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.api.model.Avatar
import com.vrcx.android.data.repository.AvatarRepository
import com.vrcx.android.data.util.runCatchingCancellable
import com.vrcx.android.di.DefaultDispatcher
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
import kotlinx.coroutines.launch

@HiltViewModel
class AvatarsViewModel @Inject constructor(
    private val avatarRepository: AvatarRepository,
    @DefaultDispatcher defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedVisibility = MutableStateFlow<String?>(null)
    val selectedVisibility: StateFlow<String?> = _selectedVisibility.asStateFlow()

    private val _selectedPlatform = MutableStateFlow<String?>(null)
    val selectedPlatform: StateFlow<String?> = _selectedPlatform.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val filteredAvatars: StateFlow<List<Avatar>> =
        combine(
            avatarRepository.myAvatars,
            _searchQuery,
            _selectedVisibility,
            _selectedPlatform,
        ) { avatars, query, visibility, platform ->
            avatars
                .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
                .filter { visibility == null || it.releaseStatus == visibility }
                .filter {
                    platform == null || it.unityPackages.any { unity -> unity.platform == platform }
                }
        }.flowOn(defaultDispatcher)
            .stateIn(viewModelScope, whileUiSubscribed, emptyList())

    init {
        loadAvatars()
    }

    fun loadAvatars() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                _error.value =
                    runCatchingCancellable { avatarRepository.loadMyAvatars() }
                        .exceptionOrNull()
                        ?.let { it.message ?: "Failed to load avatars" }
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateSearch(query: String) {
        _searchQuery.value = query
    }

    fun toggleVisibility(visibility: String) {
        _selectedVisibility.value =
            visibility.takeUnless { _selectedVisibility.value == visibility }
    }

    fun togglePlatform(platform: String) {
        _selectedPlatform.value = platform.takeUnless { _selectedPlatform.value == platform }
    }
}
