package com.vrcx.android.ui.screen.avatars

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.api.model.Avatar
import com.vrcx.android.data.repository.AvatarRepository
import com.vrcx.android.data.repository.FavoriteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AvatarDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val avatarRepository: AvatarRepository,
    private val favoriteRepository: FavoriteRepository,
) : ViewModel() {
    val avatarId: String = savedStateHandle.get<String>("avatarId") ?: ""

    private val _avatar = MutableStateFlow<Avatar?>(null)
    val avatar: StateFlow<Avatar?> = _avatar.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _isFavorited = MutableStateFlow(false)
    val isFavorited: StateFlow<Boolean> = _isFavorited.asStateFlow()

    init { loadAvatar() }

    fun loadAvatar() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val a = avatarRepository.getAvatar(avatarId)
                _avatar.value = a
                _isFavorited.value = favoriteRepository.favorites.value.any {
                    it.type == "avatar" && it.favoriteId == avatarId
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load avatar"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun selectAvatar() {
        viewModelScope.launch {
            try {
                avatarRepository.selectAvatar(avatarId)
                _message.value = "Avatar selected"
            } catch (e: Exception) {
                _message.value = "Failed: ${e.message}"
            }
        }
    }

    fun toggleFavorite() {
        viewModelScope.launch {
            try {
                if (_isFavorited.value) {
                    val fav = favoriteRepository.favorites.value.firstOrNull {
                        it.type == "avatar" && it.favoriteId == avatarId
                    }
                    if (fav != null) {
                        favoriteRepository.deleteFavorite(fav.id)
                        _isFavorited.value = false
                        _message.value = "Removed from favorites"
                    }
                } else {
                    favoriteRepository.addFavorite("avatar", avatarId, listOf("avatars1"))
                    _isFavorited.value = true
                    _message.value = "Added to favorites"
                }
            } catch (e: Exception) {
                _message.value = "Failed: ${e.message}"
            }
        }
    }

    fun clearMessage() { _message.value = null }
}
