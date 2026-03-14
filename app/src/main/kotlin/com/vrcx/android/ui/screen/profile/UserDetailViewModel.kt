package com.vrcx.android.ui.screen.profile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.api.FriendApi
import com.vrcx.android.data.api.PlayerModerationApi
import com.vrcx.android.data.api.model.PlayerModerationRequest
import com.vrcx.android.data.api.model.VrcUser
import com.vrcx.android.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UserDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val userRepository: UserRepository,
    private val friendApi: FriendApi,
    private val playerModerationApi: PlayerModerationApi,
) : ViewModel() {

    private val userId: String = savedStateHandle.get<String>("userId") ?: ""

    private val _user = MutableStateFlow<VrcUser?>(null)
    val user: StateFlow<VrcUser?> = _user.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        loadUser()
    }

    fun loadUser() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                _user.value = userRepository.getUser(userId, forceRefresh = true)
            } catch (e: Exception) {
                _message.value = "Failed to load user: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun sendFriendRequest() {
        viewModelScope.launch {
            try {
                friendApi.sendFriendRequest(userId)
                _message.value = "Friend request sent"
                loadUser()
            } catch (e: Exception) {
                _message.value = "Failed: ${e.message}"
            }
        }
    }

    fun unfriend() {
        viewModelScope.launch {
            try {
                friendApi.deleteFriend(userId)
                _message.value = "Unfriended"
                loadUser()
            } catch (e: Exception) {
                _message.value = "Failed: ${e.message}"
            }
        }
    }

    fun blockUser() {
        viewModelScope.launch {
            try {
                playerModerationApi.sendPlayerModeration(PlayerModerationRequest(userId, "block"))
                _message.value = "User blocked"
            } catch (e: Exception) {
                _message.value = "Failed: ${e.message}"
            }
        }
    }

    fun muteUser() {
        viewModelScope.launch {
            try {
                playerModerationApi.sendPlayerModeration(PlayerModerationRequest(userId, "mute"))
                _message.value = "User muted"
            } catch (e: Exception) {
                _message.value = "Failed: ${e.message}"
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}
