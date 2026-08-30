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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.vrcx.android.data.api.model.FavoriteGroup
import com.vrcx.android.ui.common.LoadState
import com.vrcx.android.ui.common.UiStateContainer
import com.vrcx.android.ui.common.prettyVisibility
import com.vrcx.android.ui.components.ConfirmDialog
import com.vrcx.android.ui.components.EmptyState
import com.vrcx.android.ui.components.UserListItem
import com.vrcx.android.ui.components.VrcxCard
import com.vrcx.android.ui.components.VrcxDetailTopBar
import com.vrcx.android.ui.components.VrcxTabRow
import com.vrcx.android.ui.components.WorldListItem

private data class FavoriteSection(
    val key: String,
    val title: String,
    val visibility: String? = null,
    val items: List<ResolvedFavorite>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    viewModel: FavoritesViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
    onUserClick: (String) -> Unit = {},
    onWorldClick: (String) -> Unit = {},
    onAvatarClick: (String) -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val resolvedFavorites by viewModel.resolvedFavorites.collectAsStateWithLifecycle()
    val favoriteGroups by viewModel.favoriteGroups.collectAsStateWithLifecycle()
    val selectedTab = uiState.selectedTab
    var pendingUnfavorite by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        VrcxDetailTopBar(title = "Favorites", onBack = onBack)
        VrcxTabRow(selectedTabIndex = selectedTab.ordinal) {
            FavoritesTab.entries.forEach { tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { viewModel.selectTab(tab) },
                    text = { Text(tab.label) },
                )
            }
        }
        FavoritesContent(
            uiState = uiState,
            favorites = resolvedFavorites,
            groups = favoriteGroups,
            actions =
                FavoriteScreenActions(
                    onRetry = viewModel::retry,
                    onRefresh = viewModel::refresh,
                    onUserClick = onUserClick,
                    onWorldClick = onWorldClick,
                    onAvatarClick = onAvatarClick,
                    onUnfavorite = { pendingUnfavorite = it },
                ),
        )
    }

    pendingUnfavorite?.let { id ->
        ConfirmDialog(
            title = "Remove Favorite",
            message = "Remove this from your favorites?",
            confirmLabel = "Remove",
            onConfirm = {
                viewModel.unfavorite(id)
                pendingUnfavorite = null
            },
            onDismiss = { pendingUnfavorite = null },
        )
    }
}

private data class FavoriteScreenActions(
    val onRetry: () -> Unit,
    val onRefresh: () -> Unit,
    val onUserClick: (String) -> Unit,
    val onWorldClick: (String) -> Unit,
    val onAvatarClick: (String) -> Unit,
    val onUnfavorite: (String) -> Unit,
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun FavoritesContent(
    uiState: FavoritesUiState,
    favorites: List<ResolvedFavorite>,
    groups: List<FavoriteGroup>,
    actions: FavoriteScreenActions,
) {
    val selectedTab = uiState.selectedTab
    val selectedTabState = uiState.selectedTabState
    val types = selectedTab.favoriteTypes
    val filtered =
        remember(favorites, types) {
            favorites.filter { it.favorite.type in types }
        }
    val sections =
        remember(filtered, groups, types) {
            buildFavoriteSections(
                favorites = filtered,
                groups = groups.filter { it.type in types },
            )
        }

    val inlineError =
        (selectedTabState as? LoadState.Loaded)?.let { it.warning ?: it.staleError }
    inlineError?.let { FavoriteInlineError(it, actions.onRetry) }
    UiStateContainer(
        isLoading = selectedTabState is LoadState.Loading,
        error = (selectedTabState as? LoadState.Failed)?.message,
        isEmpty = false,
        onRetry = actions.onRetry,
        modifier = Modifier.fillMaxSize(),
    ) {
        PullToRefreshBox(
            isRefreshing = (selectedTabState as? LoadState.Loaded)?.isRefreshing == true,
            onRefresh = actions.onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            FavoriteList(selectedTab, filtered, sections, actions)
        }
    }
}

@Composable
private fun FavoriteInlineError(message: String, onRetry: () -> Unit) {
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
        TextButton(onClick = onRetry) {
            Text("Retry")
        }
    }
}

@Composable
private fun FavoriteList(
    selectedTab: FavoritesTab,
    favorites: List<ResolvedFavorite>,
    sections: List<FavoriteSection>,
    actions: FavoriteScreenActions,
) {
    val typeKey = selectedTab.name.lowercase()
    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (favorites.isEmpty()) {
            item(key = "empty-$typeKey") {
                EmptyState(
                    message = "No ${selectedTab.label.lowercase()} favorites",
                    icon = Icons.Outlined.FavoriteBorder,
                    modifier = Modifier.fillParentMaxSize(),
                )
            }
        } else {
            item(key = "summary-$typeKey") {
                val groupSummary =
                    if (sections.size > 1) " across ${sections.size} groups" else ""
                Text(
                    text = "${favorites.size} favorites$groupSummary",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            sections.forEach { section ->
                item(key = "header-${section.key}") {
                    FavoriteSectionHeader(section)
                }
                items(section.items, key = { "${section.key}-${it.favorite.id}" }) { favorite ->
                    FavoriteItem(favorite, actions)
                }
            }
        }
    }
}

@Composable
private fun FavoriteItem(favorite: ResolvedFavorite, actions: FavoriteScreenActions) {
    when (favorite.favorite.type) {
        "friend" ->
            UserListItem(
                avatarUrl = favorite.thumbnailUrl.ifEmpty { null },
                displayName = favorite.name,
                subtitle = favorite.subtitle.ifBlank { "Tap to open profile" },
                state = favorite.friendState,
                status = favorite.friendStatus,
                onClick = { actions.onUserClick(favorite.favorite.favoriteId) },
                trailing = {
                    UnfavoriteButton { actions.onUnfavorite(favorite.favorite.id) }
                },
            )

        "world", "vrcPlusWorld" ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                WorldListItem(
                    thumbnailUrl = favorite.thumbnailUrl,
                    name = favorite.name,
                    authorName = favorite.subtitle,
                    onClick = { actions.onWorldClick(favorite.favorite.favoriteId) },
                    modifier = Modifier.weight(1f),
                )
                UnfavoriteButton { actions.onUnfavorite(favorite.favorite.id) }
            }

        else ->
            AvatarFavoriteItem(
                favorite = favorite,
                onClick = { actions.onAvatarClick(favorite.favorite.favoriteId) },
                onUnfavorite = { actions.onUnfavorite(favorite.favorite.id) },
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
private fun AvatarFavoriteItem(favorite: ResolvedFavorite, onClick: () -> Unit, onUnfavorite: () -> Unit) {
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
        UnfavoriteButton(onUnfavorite)
    }
}

@Composable
private fun UnfavoriteButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Outlined.Delete, "Unfavorite", tint = MaterialTheme.colorScheme.error)
    }
}

private fun buildFavoriteSections(
    favorites: List<ResolvedFavorite>,
    groups: List<FavoriteGroup>,
): List<FavoriteSection> {
    if (favorites.isEmpty()) return emptyList()

    val groupMap = groups.associateBy { it.name }
    val itemsByTag = LinkedHashMap<String, MutableList<ResolvedFavorite>>()
    favorites.forEach { favorite ->
        favorite.groupTags.forEach { tag ->
            itemsByTag.getOrPut(tag) { mutableListOf() } += favorite
        }
    }

    // Group order first, then any tag the server sent that has no group.
    val orderedTags = LinkedHashSet<String>().apply {
        groups.forEach { add(it.name) }
        addAll(itemsByTag.keys)
    }

    val tagSections = orderedTags.mapNotNull { tag ->
        val sectionItems = itemsByTag[tag] ?: return@mapNotNull null
        val fallbackName = tag.prettyFavoriteGroupName()
        FavoriteSection(
            key = tag,
            title = groupMap[tag]?.displayName?.ifBlank { fallbackName } ?: fallbackName,
            visibility = groupMap[tag]?.visibility,
            items = sectionItems,
        )
    }

    val ungroupedItems = favorites.filter { it.groupTags.isEmpty() }
    return if (ungroupedItems.isEmpty()) {
        tagSections
    } else {
        tagSections + FavoriteSection(
            key = "__ungrouped",
            title = "Ungrouped",
            items = ungroupedItems,
        )
    }
}

private fun String.prettyFavoriteGroupName(): String = when {
    startsWith("group_") -> "Group ${substringAfter("group_").toIntOrNull()?.plus(1) ?: 1}"
    startsWith("worlds") -> "Worlds ${substringAfter("worlds")}"
    startsWith("avatars") -> "Avatars ${substringAfter("avatars")}"
    else -> replace('_', ' ').replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}
