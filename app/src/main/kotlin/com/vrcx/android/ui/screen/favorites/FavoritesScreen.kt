package com.vrcx.android.ui.screen.favorites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.api.model.Avatar
import com.vrcx.android.data.api.model.Favorite
import com.vrcx.android.data.api.model.FavoriteGroup
import com.vrcx.android.data.api.model.World
import com.vrcx.android.data.api.model.displayAvatarUrl
import com.vrcx.android.data.model.FriendState
import com.vrcx.android.data.repository.FavoriteRepository
import com.vrcx.android.data.repository.FriendRepository
import com.vrcx.android.data.repository.UserRepository
import com.vrcx.android.ui.common.UiStateContainer
import com.vrcx.android.ui.components.ConfirmDialog
import com.vrcx.android.ui.common.prettyVisibility
import com.vrcx.android.ui.components.UserListItem
import com.vrcx.android.ui.components.VrcxCard
import com.vrcx.android.ui.components.VrcxDetailTopBar
import com.vrcx.android.ui.components.WorldListItem
import com.vrcx.android.ui.theme.LocalWallpaperActive
import coil3.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import javax.inject.Inject

data class ResolvedFavorite(
    val favorite: Favorite,
    val name: String,
    val thumbnailUrl: String = "",
    val subtitle: String = "",
    val groupTags: List<String> = emptyList(),
    val friendState: FriendState? = null,
    val friendStatus: String? = null,
)

private data class FavoriteSection(
    val key: String,
    val title: String,
    val visibility: String? = null,
    val items: List<ResolvedFavorite>,
)

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val favoriteRepository: FavoriteRepository,
    private val friendRepository: FriendRepository,
    private val userRepository: UserRepository,
) : ViewModel() {
    private val _resolvedFavorites = MutableStateFlow<List<ResolvedFavorite>>(emptyList())
    val resolvedFavorites: StateFlow<List<ResolvedFavorite>> = _resolvedFavorites.asStateFlow()

    val favoriteGroups: StateFlow<List<FavoriteGroup>> = favoriteRepository.favoriteGroups

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _warning = MutableStateFlow<String?>(null)
    val warning: StateFlow<String?> = _warning.asStateFlow()

    init {
        // Start the favorites flow collector up front so it keeps running even
        // if one of the bulk prefetches below throws. Otherwise a single
        // network hiccup would leave the UI subscribed to nothing for the rest
        // of this ViewModel's lifetime, including friend favorites that don't
        // actually depend on the /worlds/favorites or /avatars/favorites
        // bulk endpoints.
        //
        // _isLoading is owned exclusively by the preload coroutine below —
        // collectAndResolve() intentionally does not touch it. Flipping it to
        // false the first time combine() emits would fire the "no favorites"
        // empty state during the initial StateFlow seed (all three lists
        // empty) before any fetch has actually run, producing a flicker on
        // every screen entry and a false-negative empty state on slow or
        // flaky networks.
        viewModelScope.launch { collectAndResolve() }
        preload()
    }

    fun retry() {
        if (!_isLoading.value) preload()
    }

    private fun preload() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            _warning.value = null
            val failures = supervisorScope {
                listOf(
                    async { captureFailure { favoriteRepository.loadFavorites() } },
                    async { captureFailure { favoriteRepository.loadFavoriteGroups() } },
                    async { captureFailure { favoriteRepository.loadFavoriteWorldsBulk() } },
                    async { captureFailure { favoriteRepository.loadFavoriteAvatarsBulk() } },
                ).awaitAll().filterNotNull()
            }
            when {
                failures.size == 4 -> _error.value = "Failed to load favorites"
                failures.isNotEmpty() -> _warning.value =
                    "Some favorite sections could not be loaded."
            }
            _isLoading.value = false
        }
    }

    private suspend fun captureFailure(block: suspend () -> Unit): Throwable? = try {
        block()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e
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
        // _isLoading is owned by the preload coroutine (see init); don't touch
        // it here. Toggling it on every combine() emission would either flash
        // the loading spinner on routine updates or race with the preload
        // completion and clear the flag before fetches actually finish.
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
                            result.add(
                                ResolvedFavorite(
                                    favorite = fav,
                                    name = cachedFriend.name,
                                    thumbnailUrl = cachedFriend.ref?.displayAvatarUrl().orEmpty(),
                                    subtitle = cachedFriend.ref?.statusDescription ?: "",
                                    groupTags = fav.tags,
                                    friendState = cachedFriend.state,
                                    friendStatus = cachedFriend.ref?.status,
                                )
                            )
                        } else {
                            val user = userRepository.getUser(fav.favoriteId)
                            result.add(
                                ResolvedFavorite(
                                    favorite = fav,
                                    name = user.displayName,
                                    thumbnailUrl = user.displayAvatarUrl(),
                                    subtitle = user.statusDescription,
                                    groupTags = fav.tags,
                                )
                            )
                        }
                    }
                    "world", "vrcPlusWorld" -> {
                        val world = worldsById[fav.favoriteId]
                        if (world != null) {
                            result.add(
                                ResolvedFavorite(
                                    favorite = fav,
                                    name = world.name,
                                    thumbnailUrl = world.thumbnailImageUrl,
                                    subtitle = world.authorName,
                                    groupTags = fav.tags,
                                )
                            )
                        } else {
                            result.add(ResolvedFavorite(favorite = fav, name = fav.favoriteId, groupTags = fav.tags))
                        }
                    }
                    "avatar" -> {
                        val avatar = avatarsById[fav.favoriteId]
                        if (avatar != null) {
                            result.add(
                                ResolvedFavorite(
                                    favorite = fav,
                                    name = avatar.name,
                                    thumbnailUrl = avatar.thumbnailImageUrl,
                                    subtitle = "by ${avatar.authorName}",
                                    groupTags = fav.tags,
                                )
                            )
                        } else {
                            result.add(ResolvedFavorite(favorite = fav, name = fav.favoriteId, groupTags = fav.tags))
                        }
                    }
                    else -> result.add(ResolvedFavorite(favorite = fav, name = fav.favoriteId, groupTags = fav.tags))
                }
            } catch (e: Exception) {
                result.add(ResolvedFavorite(favorite = fav, name = fav.favoriteId, groupTags = fav.tags))
            }
        }
        _resolvedFavorites.value = result
    }

    fun unfavorite(favoriteId: String) {
        viewModelScope.launch {
            try {
                favoriteRepository.deleteFavorite(favoriteId)
                _resolvedFavorites.value = _resolvedFavorites.value.filter { it.favorite.id != favoriteId }
            } catch (_: Exception) {}
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    viewModel: FavoritesViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
    onUserClick: (String) -> Unit = {},
    onWorldClick: (String) -> Unit = {},
    onAvatarClick: (String) -> Unit = {},
) {
    val resolvedFavorites by viewModel.resolvedFavorites.collectAsStateWithLifecycle()
    val favoriteGroups by viewModel.favoriteGroups.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val warning by viewModel.warning.collectAsStateWithLifecycle()
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
        val types = when (selectedTab) {
            0 -> setOf("friend")
            1 -> setOf("world", "vrcPlusWorld")
            else -> setOf("avatar")
        }
        val typeKey = listOf("friend", "world", "avatar")[selectedTab]
        val filtered = resolvedFavorites.filter { it.favorite.type in types }
        val sections = remember(filtered, favoriteGroups, types) {
            buildFavoriteSections(
                favorites = filtered,
                groups = favoriteGroups.filter { it.type in types },
            )
        }

        warning?.let { message ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = viewModel::retry) { Text("Retry") }
            }
        }

        UiStateContainer(
            isLoading = isLoading,
            error = error,
            isEmpty = filtered.isEmpty(),
            onRetry = viewModel::retry,
            emptyMessage = "No ${tabs[selectedTab].lowercase()} favorites",
            emptyIcon = Icons.Outlined.FavoriteBorder,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "summary-$typeKey") {
                    Text(
                        text = "${filtered.size} favorites${if (sections.size > 1) " across ${sections.size} groups" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                sections.forEach { section ->
                    item(key = "header-${section.key}") {
                        FavoriteSectionHeader(section = section)
                    }
                    items(section.items, key = { "${section.key}-${it.favorite.id}" }) { res ->
                        when (res.favorite.type) {
                            "friend" -> UserListItem(
                                avatarUrl = res.thumbnailUrl.ifEmpty { null },
                                displayName = res.name,
                                subtitle = res.subtitle.ifBlank { "Tap to open profile" },
                                state = res.friendState,
                                status = res.friendStatus,
                                onClick = { onUserClick(res.favorite.favoriteId) },
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
                                    onClick = { onWorldClick(res.favorite.favoriteId) },
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = { pendingUnfavorite = res.favorite.id }) {
                                    Icon(Icons.Outlined.Delete, "Unfavorite", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                            else -> AvatarFavoriteItem(
                                favorite = res,
                                onClick = { onAvatarClick(res.favorite.favoriteId) },
                                onUnfavorite = { pendingUnfavorite = res.favorite.id },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingUnfavorite?.let { id ->
        ConfirmDialog(
            title = "Remove Favorite",
            message = "Remove this from your favorites?",
            confirmLabel = "Remove",
            onConfirm = { viewModel.unfavorite(id); pendingUnfavorite = null },
            onDismiss = { pendingUnfavorite = null },
        )
    }
}

@Composable
private fun FavoriteSectionHeader(section: FavoriteSection) {
    Text(
        text = buildString {
            append(section.title)
            section.visibility?.takeIf { it.isNotBlank() }?.let { visibility ->
                append(" • ")
                append(visibility.prettyVisibility())
            }
            append(" • ")
            append(section.items.size)
        },
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun AvatarFavoriteItem(
    favorite: ResolvedFavorite,
    onClick: () -> Unit,
    onUnfavorite: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        VrcxCard(
            onClick = onClick,
            modifier = Modifier.weight(1f),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = favorite.thumbnailUrl,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp).clip(MaterialTheme.shapes.small),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(favorite.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = favorite.subtitle.ifBlank { "Tap to open avatar" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        IconButton(onClick = onUnfavorite) {
            Icon(Icons.Outlined.Delete, "Unfavorite", tint = MaterialTheme.colorScheme.error)
        }
    }
}

private fun buildFavoriteSections(
    favorites: List<ResolvedFavorite>,
    groups: List<FavoriteGroup>,
): List<FavoriteSection> {
    if (favorites.isEmpty()) return emptyList()

    val groupMap = groups.associateBy { it.name }
    val orderedTags = buildList {
        groups.forEach { add(it.name) }
        favorites.flatMap { it.groupTags }.distinct().forEach { tag ->
            if (tag !in this) add(tag)
        }
    }

    val sections = orderedTags.mapNotNull { tag ->
        val sectionItems = favorites.filter { tag in it.groupTags }
        if (sectionItems.isEmpty()) {
            null
        } else {
            FavoriteSection(
                key = tag,
                title = groupMap[tag]?.displayName?.ifBlank { tag.prettyFavoriteGroupName() } ?: tag.prettyFavoriteGroupName(),
                visibility = groupMap[tag]?.visibility,
                items = sectionItems,
            )
        }
    }.toMutableList()

    val ungroupedItems = favorites.filter { it.groupTags.isEmpty() }
    if (ungroupedItems.isNotEmpty()) {
        sections.add(
            FavoriteSection(
                key = "__ungrouped",
                title = "Ungrouped",
                items = ungroupedItems,
            )
        )
    }

    return sections.ifEmpty {
        listOf(
            FavoriteSection(
                key = "all",
                title = "Favorites",
                items = favorites,
            )
        )
    }
}

private fun String.prettyFavoriteGroupName(): String =
    when {
        startsWith("group_") -> "Group ${substringAfter("group_").toIntOrNull()?.plus(1) ?: 1}"
        startsWith("worlds") -> "Worlds ${substringAfter("worlds")}"
        startsWith("avatars") -> "Avatars ${substringAfter("avatars")}"
        else -> replace('_', ' ').replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

