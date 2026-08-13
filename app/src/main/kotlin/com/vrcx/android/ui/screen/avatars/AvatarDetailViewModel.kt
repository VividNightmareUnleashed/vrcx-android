package com.vrcx.android.ui.screen.avatars

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.api.model.Avatar
import com.vrcx.android.data.repository.AvatarRepository
import com.vrcx.android.data.repository.FavoriteRepository
import com.vrcx.android.ui.common.LoadState
import com.vrcx.android.ui.common.completeLoad
import com.vrcx.android.ui.common.failLoad
import com.vrcx.android.ui.common.settleLoad
import com.vrcx.android.ui.common.startLoad
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AvatarDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val avatarRepository: AvatarRepository,
    private val favoriteRepository: FavoriteRepository,
) : ViewModel() {
    val avatarId: String = savedStateHandle.get<String>("avatarId") ?: ""

    private val _avatar = MutableStateFlow<LoadState<Avatar>>(LoadState.NotLoaded)
    val avatar: StateFlow<LoadState<Avatar>> = _avatar.asStateFlow()

    /** An action's outcome rather than a load's, so it stays beside the state. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Non-null exactly when this avatar is in the favorites list. */
    private val _favoriteEntryId = MutableStateFlow<String?>(null)
    val favoriteEntryId: StateFlow<String?> = _favoriteEntryId.asStateFlow()

    init {
        observeFavoriteStatus()
        loadFavoriteStatus()
        loadAvatar()
    }

    fun loadAvatar() {
        viewModelScope.launch {
            _avatar.update { it.startLoad() }
            try {
                val avatar = avatarRepository.getAvatar(avatarId, forceRefresh = true)
                _avatar.update { it.completeLoad(avatar) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _avatar.update { it.failLoad(e.message ?: "Failed to load avatar") }
            } finally {
                _avatar.update { it.settleLoad() }
            }
        }
    }

    fun selectAvatar() {
        viewModelScope.launch {
            try {
                avatarRepository.selectAvatar(avatarId)
                _message.value = "Avatar selected"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "Failed: ${e.message}"
            }
        }
    }

    fun toggleFavorite() {
        viewModelScope.launch {
            try {
                favoriteRepository.loadFavorites(type = "avatar")
                // Read the freshly loaded list rather than the collector's
                // mirror, which has not resumed yet at this point.
                val entryId = favoriteRepository.favorites.value.firstOrNull {
                    it.type == "avatar" && it.favoriteId == avatarId
                }?.id
                if (entryId != null) {
                    favoriteRepository.deleteFavorite(entryId)
                    _message.value = "Removed from favorites"
                } else {
                    favoriteRepository.addFavorite("avatar", avatarId)
                    _message.value = "Added to favorites"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "Failed: ${e.message}"
            }
        }
    }

    fun clearMessage() { _message.value = null }

    private fun loadFavoriteStatus() {
        viewModelScope.launch {
            try {
                favoriteRepository.loadFavorites(type = "avatar")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Favorite status is ancillary to the primary avatar detail.
            }
        }
    }

    private fun observeFavoriteStatus() {
        viewModelScope.launch {
            favoriteRepository.favorites.collect { favorites ->
                _favoriteEntryId.value = favorites.firstOrNull {
                    it.type == "avatar" && it.favoriteId == avatarId
                }?.id
            }
        }
    }
}
