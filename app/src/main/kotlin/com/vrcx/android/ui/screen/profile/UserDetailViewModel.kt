package com.vrcx.android.ui.screen.profile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.api.FriendApi
import com.vrcx.android.data.api.GroupApi
import com.vrcx.android.data.api.NotificationApi
import com.vrcx.android.data.api.PlayerModerationApi
import com.vrcx.android.data.api.WorldApi
import com.vrcx.android.data.api.model.Group
import com.vrcx.android.data.api.model.PlayerModerationRequest
import com.vrcx.android.data.api.model.VrcUser
import com.vrcx.android.data.api.model.World
import com.vrcx.android.data.db.dao.FriendNotifyDao
import com.vrcx.android.data.db.dao.MemoDao
import com.vrcx.android.data.db.dao.NoteDao
import com.vrcx.android.data.db.entity.MemoEntity
import com.vrcx.android.data.db.entity.NoteEntity
import com.vrcx.android.data.cache.ProfilePicCacheManager
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.data.repository.FavoriteRepository
import com.vrcx.android.data.repository.FriendRepository
import com.vrcx.android.data.repository.UserRepository
import kotlinx.coroutines.Dispatchers
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
    private val groupApi: GroupApi,
    private val worldApi: WorldApi,
    private val notificationApi: NotificationApi,
    private val favoriteRepository: FavoriteRepository,
    private val authRepository: AuthRepository,
    private val noteDao: NoteDao,
    private val memoDao: MemoDao,
    private val friendNotifyDao: FriendNotifyDao,
    private val friendRepository: FriendRepository,
    private val profilePicCacheManager: ProfilePicCacheManager,
) : ViewModel() {

    val userId: String = savedStateHandle.get<String>("userId") ?: ""

    private val _user = MutableStateFlow<VrcUser?>(null)
    val user: StateFlow<VrcUser?> = _user.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _selectedTab = MutableStateFlow(0)
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    private val _userGroups = MutableStateFlow<List<Group>>(emptyList())
    val userGroups: StateFlow<List<Group>> = _userGroups.asStateFlow()

    private val _userWorlds = MutableStateFlow<List<World>>(emptyList())
    val userWorlds: StateFlow<List<World>> = _userWorlds.asStateFlow()

    private val _isFavorited = MutableStateFlow(false)
    val isFavorited: StateFlow<Boolean> = _isFavorited.asStateFlow()

    private val _memo = MutableStateFlow<String?>(null)
    val memo: StateFlow<String?> = _memo.asStateFlow()

    private val _notifyEnabled = MutableStateFlow(false)
    val notifyEnabled: StateFlow<Boolean> = _notifyEnabled.asStateFlow()

    private var favoriteEntryId: String? = null

    init { loadUser() }

    fun loadUser() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                _user.value = userRepository.getUser(userId, forceRefresh = true)
                // Check favorite status
                val favs = favoriteRepository.favorites.value
                val fav = favs.firstOrNull { it.type == "friend" && it.favoriteId == userId }
                _isFavorited.value = fav != null
                favoriteEntryId = fav?.id
                // Load memo
                val ownerUserId = (authRepository.authState.value as? AuthState.LoggedIn)?.user?.id ?: ""
                if (ownerUserId.isNotEmpty()) {
                    val memoEntity = memoDao.getMemo("$ownerUserId:$userId")
                    _memo.value = memoEntity?.memo
                    // Check notification enabled state
                    val notifyEntity = friendNotifyDao.get("$ownerUserId:$userId")
                    _notifyEnabled.value = notifyEntity != null
                }
                // Cache profile picture to disk
                viewModelScope.launch(Dispatchers.IO) {
                    val u = _user.value ?: return@launch
                    val imageUrl = u.profilePicOverride.ifEmpty { u.currentAvatarThumbnailImageUrl }
                    if (imageUrl.isNotEmpty()) {
                        try { profilePicCacheManager.cacheImage(imageUrl) } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                _message.value = "Failed to load user: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun selectTab(tab: Int) {
        _selectedTab.value = tab
        when (tab) {
            1 -> if (_userGroups.value.isEmpty()) loadGroups()
            2 -> if (_userWorlds.value.isEmpty()) loadWorlds()
        }
    }

    private fun loadGroups() {
        viewModelScope.launch {
            try {
                _userGroups.value = groupApi.getUserGroups(userId)
            } catch (_: Exception) {}
        }
    }

    private fun loadWorlds() {
        viewModelScope.launch {
            try {
                _userWorlds.value = worldApi.getWorlds(user = userId)
            } catch (_: Exception) {}
        }
    }

    fun toggleFavorite() {
        viewModelScope.launch {
            try {
                if (_isFavorited.value && favoriteEntryId != null) {
                    favoriteRepository.deleteFavorite(favoriteEntryId!!)
                    _isFavorited.value = false
                    favoriteEntryId = null
                    _message.value = "Removed from favorites"
                } else {
                    val result = favoriteRepository.addFavorite("friend", userId, listOf("group_0"))
                    _isFavorited.value = true
                    favoriteEntryId = result.id
                    _message.value = "Added to favorites"
                }
            } catch (e: Exception) {
                _message.value = "Failed: ${e.message}"
            }
        }
    }

    fun requestInvite() {
        viewModelScope.launch {
            try {
                notificationApi.sendRequestInvite(userId)
                _message.value = "Invite requested"
            } catch (e: Exception) {
                _message.value = "Failed: ${e.message}"
            }
        }
    }

    fun sendInvite() {
        viewModelScope.launch {
            try {
                val location = (authRepository.authState.value as? AuthState.LoggedIn)?.user?.location
                if (location.isNullOrEmpty() || location == "offline" || location == "private") {
                    _message.value = "You must be in a world to send invites"
                    return@launch
                }
                val worldId = location.substringBefore(":")
                val instanceId = location.substringAfter(":")
                notificationApi.sendInvite(userId, mapOf("instanceId" to instanceId, "worldId" to worldId))
                _message.value = "Invite sent"
            } catch (e: Exception) {
                _message.value = "Failed: ${e.message}"
            }
        }
    }

    fun saveMemo(text: String) {
        viewModelScope.launch {
            try {
                val ownerUserId = (authRepository.authState.value as? AuthState.LoggedIn)?.user?.id ?: return@launch
                memoDao.insertMemo(MemoEntity(
                    odUserId = "$ownerUserId:$userId",
                    ownerUserId = ownerUserId,
                    memo = text,
                    editedAt = java.time.Instant.now().toString(),
                ))
                _memo.value = text
                _message.value = "Memo saved"
            } catch (e: Exception) {
                _message.value = "Failed: ${e.message}"
            }
        }
    }

    fun toggleNotify() {
        viewModelScope.launch {
            try {
                val newState = friendRepository.toggleFriendNotify(userId)
                _notifyEnabled.value = newState
                _message.value = if (newState) "Notifications enabled" else "Notifications disabled"
            } catch (e: Exception) {
                _message.value = "Failed: ${e.message}"
            }
        }
    }

    fun sendFriendRequest() {
        viewModelScope.launch {
            try {
                friendApi.sendFriendRequest(userId)
                _message.value = "Friend request sent"
                loadUser()
            } catch (e: Exception) { _message.value = "Failed: ${e.message}" }
        }
    }

    fun unfriend() {
        viewModelScope.launch {
            try {
                friendApi.deleteFriend(userId)
                _message.value = "Unfriended"
                loadUser()
            } catch (e: Exception) { _message.value = "Failed: ${e.message}" }
        }
    }

    fun blockUser() {
        viewModelScope.launch {
            try {
                playerModerationApi.sendPlayerModeration(PlayerModerationRequest(userId, "block"))
                _message.value = "User blocked"
            } catch (e: Exception) { _message.value = "Failed: ${e.message}" }
        }
    }

    fun muteUser() {
        viewModelScope.launch {
            try {
                playerModerationApi.sendPlayerModeration(PlayerModerationRequest(userId, "mute"))
                _message.value = "User muted"
            } catch (e: Exception) { _message.value = "Failed: ${e.message}" }
        }
    }

    fun hideAvatar() {
        viewModelScope.launch {
            try {
                playerModerationApi.sendPlayerModeration(PlayerModerationRequest(userId, "hideAvatar"))
                _message.value = "Avatar hidden"
            } catch (e: Exception) { _message.value = "Failed: ${e.message}" }
        }
    }

    fun showAvatar() {
        viewModelScope.launch {
            try {
                playerModerationApi.sendPlayerModeration(PlayerModerationRequest(userId, "showAvatar"))
                _message.value = "Avatar shown"
            } catch (e: Exception) { _message.value = "Failed: ${e.message}" }
        }
    }

    fun clearMessage() { _message.value = null }
}
