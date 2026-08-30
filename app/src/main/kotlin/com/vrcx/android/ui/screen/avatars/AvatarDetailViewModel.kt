package com.vrcx.android.ui.screen.avatars

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.api.model.Avatar
import com.vrcx.android.data.repository.AvatarRepository
import com.vrcx.android.data.repository.FavoriteRepository
import com.vrcx.android.data.util.runCatchingCancellable
import com.vrcx.android.data.util.runIgnoringFailure
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

@HiltViewModel
class AvatarDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val avatarRepository: AvatarRepository,
    private val favoriteRepository: FavoriteRepository,
) : ViewModel() {
    val avatarId: String = savedStateHandle.get<String>("avatarId").orEmpty()

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
                runCatchingCancellable {
                    avatarRepository.getAvatar(avatarId, forceRefresh = true)
                }.fold(
                    onSuccess = { avatar -> _avatar.update { it.completeLoad(avatar) } },
                    onFailure = { failure ->
                        _avatar.update {
                            it.failLoad(failure.message ?: "Failed to load avatar")
                        }
                    },
                )
            } finally {
                _avatar.update { it.settleLoad() }
            }
        }
    }

    fun selectAvatar() {
        viewModelScope.launch {
            _message.value =
                runCatchingCancellable {
                    avatarRepository.selectAvatar(avatarId)
                    "Avatar selected"
                }.getOrElse { failure -> "Failed: ${failure.message}" }
        }
    }

    fun toggleFavorite() {
        viewModelScope.launch {
            _message.value =
                runCatchingCancellable {
                    favoriteRepository.loadFavorites(type = "avatar")
                    // The repository value is updated before its collector can resume.
                    val entryId =
                        favoriteRepository.favorites.value
                            .firstOrNull { it.type == "avatar" && it.favoriteId == avatarId }
                            ?.id
                    if (entryId != null) {
                        favoriteRepository.deleteFavorite(entryId)
                        "Removed from favorites"
                    } else {
                        favoriteRepository.addFavorite("avatar", avatarId)
                        "Added to favorites"
                    }
                }.getOrElse { failure -> "Failed: ${failure.message}" }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    private fun loadFavoriteStatus() {
        viewModelScope.launch {
            runIgnoringFailure {
                favoriteRepository.loadFavorites(type = "avatar")
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
