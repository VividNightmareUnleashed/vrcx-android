package com.vrcx.android.ui.screen.favorites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.api.model.Avatar
import com.vrcx.android.data.api.model.Favorite
import com.vrcx.android.data.api.model.World
import com.vrcx.android.data.repository.FavoriteRepository
import com.vrcx.android.data.repository.FriendRepository
import com.vrcx.android.data.repository.UserRepository
import com.vrcx.android.ui.common.UiStateContainer
import com.vrcx.android.ui.components.UserListItem
import com.vrcx.android.ui.components.VrcxCard
import com.vrcx.android.ui.components.VrcxDetailTopBar
import com.vrcx.android.ui.components.WorldListItem
import com.vrcx.android.ui.theme.LocalWallpaperActive
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ResolvedFavorite(
    val favorite: Favorite,
    val name: String,
    val thumbnailUrl: String = "",
    val subtitle: String = "",
)

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val favoriteRepository: FavoriteRepository,
    private val friendRepository: FriendRepository,
    private val userRepository: UserRepository,
) : ViewModel() {
    private val _resolvedFavorites = MutableStateFlow<List<ResolvedFavorite>>(emptyList())
    val resolvedFavorites: StateFlow<List<ResolvedFavorite>> = _resolvedFavorites.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                // Friend favorites still need per-id resolution because VRChat has no
                // /users/favorites bulk endpoint, but worlds and avatars get a single
                // paginated GET each via /worlds/favorites and /avatars/favorites.
                favoriteRepository.loadFavorites()
                favoriteRepository.loadFavoriteGroups()
                favoriteRepository.loadFavoriteWorldsBulk()
                favoriteRepository.loadFavoriteAvatarsBulk()
                viewModelScope.launch { collectAndResolve() }
            } catch (e: Exception) {
                _resolvedFavorites.value = emptyList()
            }
            _isLoading.value = false
        }
    }

    private suspend fun collectAndResolve() {
        kotlinx.coroutines.flow.combine(
            favoriteRepository.favorites,
            favoriteRepository.favoriteWorlds,
            favoriteRepository.favoriteAvatars,
        ) { favorites, worlds, avatars ->
            Triple(favorites, worlds, avatars)
        }.collect { (favorites, worlds, avatars) ->
            resolveEntities(favorites, worlds, avatars)
        }
    }

    private suspend fun resolveEntities(
        favorites: List<Favorite>,
        worlds: List<World>,
        avatars: List<Avatar>,
    ) {
        _isLoading.value = true
        val worldsById = worlds.associateBy { it.id }
        val avatarsById = avatars.associateBy { it.id }
        val friends = friendRepository.friends.value
        val result = mutableListOf<ResolvedFavorite>()
        for (fav in favorites) {
            try {
                when (fav.type) {
                    "friend" -> {
                        val cachedFriend = friends[fav.favoriteId]
                        if (cachedFriend != null) {
                            result.add(ResolvedFavorite(fav, cachedFriend.name, cachedFriend.ref?.currentAvatarThumbnailImageUrl ?: "", cachedFriend.ref?.statusDescription ?: ""))
                        } else {
                            val user = userRepository.getUser(fav.favoriteId)
                            result.add(ResolvedFavorite(fav, user.displayName, user.currentAvatarThumbnailImageUrl, user.statusDescription))
                        }
                    }
                    "world" -> {
                        val world = worldsById[fav.favoriteId]
                        if (world != null) {
                            result.add(ResolvedFavorite(fav, world.name, world.thumbnailImageUrl, "by ${world.authorName}"))
                        } else {
                            result.add(ResolvedFavorite(fav, fav.favoriteId))
                        }
                    }
                    "avatar" -> {
                        val avatar = avatarsById[fav.favoriteId]
                        if (avatar != null) {
                            result.add(ResolvedFavorite(fav, avatar.name, avatar.thumbnailImageUrl, "by ${avatar.authorName}"))
                        } else {
                            result.add(ResolvedFavorite(fav, fav.favoriteId))
                        }
                    }
                    else -> result.add(ResolvedFavorite(fav, fav.favoriteId))
                }
            } catch (e: Exception) {
                result.add(ResolvedFavorite(fav, fav.favoriteId))
            }
        }
        _resolvedFavorites.value = result
        _isLoading.value = false
    }

    fun unfavorite(favoriteId: String) {
        viewModelScope.launch {
            try {
                val removed = favoriteRepository.favorites.value.firstOrNull { it.id == favoriteId }
                favoriteRepository.deleteFavorite(favoriteId)
                when (removed?.type) {
                    "world" -> favoriteRepository.dropFavoriteWorldFromCache(removed.favoriteId)
                    "avatar" -> favoriteRepository.dropFavoriteAvatarFromCache(removed.favoriteId)
                }
                _resolvedFavorites.value = _resolvedFavorites.value.filter { it.favorite.id != favoriteId }
            } catch (_: Exception) {}
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(viewModel: FavoritesViewModel = hiltViewModel(), onBack: () -> Unit = {}) {
    val resolvedFavorites by viewModel.resolvedFavorites.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Friends", "Worlds", "Avatars")
    var pendingUnfavorite by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        VrcxDetailTopBar(title = "Favorites", onBack = onBack)
        val isWallpaperActive = LocalWallpaperActive.current
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.surfaceContainer
                .let { if (isWallpaperActive) it.copy(alpha = 0.88f) else it },
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(title) })
            }
        }
        val type = listOf("friend", "world", "avatar")[selectedTab]
        val filtered = resolvedFavorites.filter { it.favorite.type == type }

        UiStateContainer(
            isLoading = isLoading,
            error = null,
            isEmpty = filtered.isEmpty(),
            emptyMessage = "No ${tabs[selectedTab].lowercase()} favorites",
            emptyIcon = Icons.Outlined.FavoriteBorder,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filtered, key = { it.favorite.id }) { res ->
                    when (res.favorite.type) {
                        "friend" -> UserListItem(
                            avatarUrl = res.thumbnailUrl.ifEmpty { null },
                            displayName = res.name,
                            subtitle = res.subtitle,
                            trailing = {
                                IconButton(onClick = { pendingUnfavorite = res.favorite.id }) {
                                    Icon(Icons.Outlined.Delete, "Unfavorite", tint = MaterialTheme.colorScheme.error)
                                }
                            },
                        )
                        "world" -> Row(verticalAlignment = Alignment.CenterVertically) {
                            WorldListItem(
                                thumbnailUrl = res.thumbnailUrl,
                                name = res.name,
                                authorName = res.subtitle,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { pendingUnfavorite = res.favorite.id }) {
                                Icon(Icons.Outlined.Delete, "Unfavorite", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                        else -> Row(verticalAlignment = Alignment.CenterVertically) {
                            WorldListItem(
                                thumbnailUrl = res.thumbnailUrl,
                                name = res.name,
                                authorName = res.subtitle,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { pendingUnfavorite = res.favorite.id }) {
                                Icon(Icons.Outlined.Delete, "Unfavorite", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    pendingUnfavorite?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingUnfavorite = null },
            title = { Text("Remove Favorite") },
            text = { Text("Remove this from your favorites?") },
            confirmButton = { TextButton(onClick = { viewModel.unfavorite(id); pendingUnfavorite = null }) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { pendingUnfavorite = null }) { Text("Cancel") } },
        )
    }
}
