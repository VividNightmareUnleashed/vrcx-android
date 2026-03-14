package com.vrcx.android.ui.screen.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.db.entity.FeedOnlineOfflineEntity
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.data.repository.FeedEntry
import com.vrcx.android.data.repository.FeedRepository
import com.vrcx.android.data.repository.FriendRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class FeedViewModel @Inject constructor(
    private val feedRepository: FeedRepository,
    private val authRepository: AuthRepository,
    private val friendRepository: FriendRepository,
) : ViewModel() {

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            _error.value = null
            try {
                friendRepository.loadFriendsList()
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to refresh"
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    val userAvatarUrls: StateFlow<Map<String, String>> = friendRepository.friends.map { friends ->
        friends.values.mapNotNull { f ->
            f.ref?.currentAvatarThumbnailImageUrl?.let { url -> f.id to url }
        }.toMap()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val userId = authRepository.authState.map { state ->
        (state as? AuthState.LoggedIn)?.user?.id ?: ""
    }

    private val _activeFilters = MutableStateFlow(setOf("gps", "status", "bio", "avatar", "online", "offline"))
    val activeFilters: StateFlow<Set<String>> = _activeFilters.asStateFlow()

    val feedEntries: StateFlow<List<FeedEntry>> = userId.flatMapLatest { uid ->
        if (uid.isEmpty()) return@flatMapLatest flowOf(emptyList<FeedEntry>())

        combine(
            feedRepository.getGpsFeed(uid),
            feedRepository.getStatusFeed(uid),
            feedRepository.getBioFeed(uid),
            feedRepository.getAvatarFeed(uid),
            feedRepository.getOnlineOfflineFeed(uid),
        ) { gps, status, bio, avatar, onlineOffline ->
            val entries = mutableListOf<FeedEntry>()
            gps.forEach {
                entries.add(FeedEntry(it.id, "gps", it.userId, it.displayName, it.worldName, it.previousLocation, it.createdAt))
            }
            status.forEach {
                entries.add(FeedEntry(it.id, "status", it.userId, it.displayName, "${it.status}: ${it.statusDescription}", "${it.previousStatus}: ${it.previousStatusDescription}", it.createdAt))
            }
            bio.forEach {
                entries.add(FeedEntry(it.id, "bio", it.userId, it.displayName, it.bio, it.previousBio, it.createdAt))
            }
            avatar.forEach {
                entries.add(FeedEntry(it.id, "avatar", it.userId, it.displayName, it.avatarName.ifEmpty { "Avatar changed" }, "", it.createdAt, it.currentAvatarThumbnailImageUrl))
            }
            onlineOffline.forEach {
                entries.add(FeedEntry(it.id, it.type, it.userId, it.displayName, it.worldName, "", it.createdAt))
            }
            entries.sortedByDescending { it.createdAt }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun toggleFilter(filter: String) {
        val current = _activeFilters.value.toMutableSet()
        if (current.contains(filter)) current.remove(filter) else current.add(filter)
        _activeFilters.value = current
    }

}
