package com.vrcx.android.ui.screen.profile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.api.AvatarApi
import com.vrcx.android.data.api.FriendApi
import com.vrcx.android.data.api.GroupApi
import com.vrcx.android.data.api.NotificationApi
import com.vrcx.android.data.api.PlayerModerationApi
import com.vrcx.android.data.api.WorldApi
import com.vrcx.android.data.api.model.Avatar
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
import com.vrcx.android.data.repository.NotificationRepository
import com.vrcx.android.data.repository.UserRepository
import kotlinx.coroutines.Dispatchers
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

private object UserDetailTab {
    const val INFO = 0
    const val MUTUALS = 1
    const val GROUPS = 2
    const val WORLDS = 3
    const val AVATARS = 4
}

@HiltViewModel
class UserDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val userRepository: UserRepository,
    private val avatarApi: AvatarApi,
    private val friendApi: FriendApi,
    private val playerModerationApi: PlayerModerationApi,
    private val groupApi: GroupApi,
    private val worldApi: WorldApi,
    private val notificationApi: NotificationApi,
    private val notificationRepository: NotificationRepository,
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

    private val _mutualFriends = MutableStateFlow<List<VrcUser>>(emptyList())
    val mutualFriends: StateFlow<List<VrcUser>> = _mutualFriends.asStateFlow()

    private val _userAvatars = MutableStateFlow<List<Avatar>>(emptyList())
    val userAvatars: StateFlow<List<Avatar>> = _userAvatars.asStateFlow()

    private val _isFavorited = MutableStateFlow(false)
    val isFavorited: StateFlow<Boolean> = _isFavorited.asStateFlow()

    private val _memo = MutableStateFlow<String?>(null)
    val memo: StateFlow<String?> = _memo.asStateFlow()

    private val _note = MutableStateFlow<String?>(null)
    val note: StateFlow<String?> = _note.asStateFlow()

    private val _notifyEnabled = MutableStateFlow(false)
    val notifyEnabled: StateFlow<Boolean> = _notifyEnabled.asStateFlow()

    private val _isTabLoading = MutableStateFlow(false)
    val isTabLoading: StateFlow<Boolean> = _isTabLoading.asStateFlow()

    private var favoriteEntryId: String? = null

    init {
        observeFavoriteStatus()
        loadUser()
    }

    fun loadUser() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                favoriteRepository.loadFavorites(type = "friend")
                val resolvedUser = userRepository.getUser(userId, forceRefresh = true)
                _user.value = resolvedUser
                // Load memo
                val ownerUserId = (authRepository.authState.value as? AuthState.LoggedIn)?.user?.id ?: ""
                if (ownerUserId.isNotEmpty()) {
                    val memoEntity = memoDao.getMemo("$ownerUserId:$userId")
                    _memo.value = memoEntity?.memo
                    val noteEntity = noteDao.get("$ownerUserId:$userId")
                    _note.value = resolvedUser.note?.takeIf { it.isNotBlank() } ?: noteEntity?.note
                    if (!resolvedUser.note.isNullOrBlank()) {
                        noteDao.insert(
                            NoteEntity(
                                compositeId = "$ownerUserId:$userId",
                                ownerUserId = ownerUserId,
                                odUserId = "$ownerUserId:$userId",
                                displayName = resolvedUser.displayName,
                                note = resolvedUser.note,
                                createdAt = Instant.now().toString(),
                            )
                        )
                    }
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
            UserDetailTab.MUTUALS -> if (_mutualFriends.value.isEmpty()) loadMutualFriends()
            UserDetailTab.GROUPS -> if (_userGroups.value.isEmpty()) loadGroups()
            UserDetailTab.WORLDS -> if (_userWorlds.value.isEmpty()) loadWorlds()
            UserDetailTab.AVATARS -> if (_userAvatars.value.isEmpty()) loadAvatars()
        }
    }

    private fun loadMutualFriends() {
        viewModelScope.launch {
            _isTabLoading.value = true
            try {
                _mutualFriends.value = userRepository.getMutualFriends(userId)
            } catch (e: Exception) {
                _message.value = "Failed to load mutual friends: ${e.message}"
            } finally {
                _isTabLoading.value = false
            }
        }
    }

    private fun loadGroups() {
        viewModelScope.launch {
            _isTabLoading.value = true
            try {
                _userGroups.value = groupApi.getUserGroups(userId)
            } catch (e: Exception) {
                _message.value = "Failed to load groups: ${e.message}"
            } finally {
                _isTabLoading.value = false
            }
        }
    }

    private fun loadWorlds() {
        viewModelScope.launch {
            _isTabLoading.value = true
            try {
                _userWorlds.value = worldApi.getWorlds(user = userId)
            } catch (e: Exception) {
                _message.value = "Failed to load worlds: ${e.message}"
            } finally {
                _isTabLoading.value = false
            }
        }
    }

    private fun loadAvatars() {
        viewModelScope.launch {
            _isTabLoading.value = true
            try {
                _userAvatars.value = avatarApi.getAvatars(
                    user = userId,
                    releaseStatus = "all",
                    n = 100,
                )
            } catch (e: Exception) {
                _message.value = "Failed to load avatars: ${e.message}"
            } finally {
                _isTabLoading.value = false
            }
        }
    }

    fun toggleFavorite() {
        viewModelScope.launch {
            try {
                if (_isFavorited.value && favoriteEntryId != null) {
                    favoriteRepository.deleteFavorite(favoriteEntryId!!)
                    _message.value = "Removed from favorites"
                } else {
                    favoriteRepository.addFavorite("friend", userId)
                    _message.value = "Added to favorites"
                }
            } catch (e: Exception) {
                _message.value = "Failed: ${e.message}"
            }
        }
    }

    private fun observeFavoriteStatus() {
        viewModelScope.launch {
            favoriteRepository.favorites.collect { favorites ->
                val favorite = favorites.firstOrNull {
                    it.type == "friend" && it.favoriteId == userId
                }
                favoriteEntryId = favorite?.id
                _isFavorited.value = favorite != null
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
                notificationRepository.sendInviteToUser(userId)
                _message.value = "Invite sent"
            } catch (e: Exception) {
                _message.value = "Failed: ${e.message}"
            }
        }
    }

    fun saveNote(text: String) {
        viewModelScope.launch {
            try {
                val ownerUserId = (authRepository.authState.value as? AuthState.LoggedIn)?.user?.id ?: return@launch
                userRepository.saveUserNote(userId, text)
                noteDao.insert(
                    NoteEntity(
                        compositeId = "$ownerUserId:$userId",
                        ownerUserId = ownerUserId,
                        odUserId = "$ownerUserId:$userId",
                        displayName = _user.value?.displayName.orEmpty(),
                        note = text,
                        createdAt = Instant.now().toString(),
                    )
                )
                _note.value = text
                _user.value = _user.value?.copy(note = text)
                _message.value = if (text.isBlank()) "Note cleared" else "Note saved"
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
                    editedAt = Instant.now().toString(),
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

    fun sendBoop() {
        viewModelScope.launch {
            try {
                userRepository.sendBoop(userId)
                _message.value = "Boop sent"
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
