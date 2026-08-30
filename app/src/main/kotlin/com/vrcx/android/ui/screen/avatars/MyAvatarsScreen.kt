@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.vrcx.android.ui.screen.avatars

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.vrcx.android.data.api.model.Avatar
import com.vrcx.android.ui.common.UiStateContainer
import com.vrcx.android.ui.common.platformLabel
import com.vrcx.android.ui.components.VrcxCard
import com.vrcx.android.ui.components.VrcxDetailTopBar
import com.vrcx.android.ui.components.VrcxSearchBar

@Composable
fun MyAvatarsScreen(
    viewModel: AvatarsViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
    onAvatarClick: (String) -> Unit = {},
) {
    val avatars by viewModel.filteredAvatars.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedVisibility by viewModel.selectedVisibility.collectAsStateWithLifecycle()
    val selectedPlatform by viewModel.selectedPlatform.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        VrcxDetailTopBar(title = "My Avatars", onBack = onBack)
        VrcxSearchBar(
            query = searchQuery,
            onQueryChange = viewModel::updateSearch,
            modifier =
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        AvatarFilters(
            selectedVisibility = selectedVisibility,
            selectedPlatform = selectedPlatform,
            onToggleVisibility = viewModel::toggleVisibility,
            onTogglePlatform = viewModel::togglePlatform,
        )
        UiStateContainer(
            isLoading = isLoading,
            error = error,
            isEmpty = avatars.isEmpty(),
            onRetry = viewModel::loadAvatars,
            emptyMessage = "No avatars",
            emptyIcon = Icons.Outlined.Face,
            emptySubtitle = "Your owned avatars will appear here",
            modifier = Modifier.fillMaxSize(),
        ) {
            AvatarGrid(avatars, onAvatarClick)
        }
    }
}

@Composable
private fun AvatarFilters(
    selectedVisibility: String?,
    selectedPlatform: String?,
    onToggleVisibility: (String) -> Unit,
    onTogglePlatform: (String) -> Unit,
) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        VISIBILITY_FILTERS.forEach { (value, label) ->
            FilterChip(
                selected = selectedVisibility == value,
                onClick = { onToggleVisibility(value) },
                label = { Text(label) },
            )
        }
        PLATFORM_FILTERS.forEach { platform ->
            FilterChip(
                selected = selectedPlatform == platform,
                onClick = { onTogglePlatform(platform) },
                label = { Text(platformLabel(platform)) },
            )
        }
    }
}

@Composable
private fun AvatarGrid(avatars: List<Avatar>, onAvatarClick: (String) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize().padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(avatars, key = { it.id }) { avatar ->
            VrcxCard(onClick = { onAvatarClick(avatar.id) }) {
                Column {
                    AsyncImage(
                        model = avatar.thumbnailImageUrl,
                        contentDescription = null,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(
                                    RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                                ),
                        contentScale = ContentScale.Crop,
                    )
                    Text(
                        text = avatar.name,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private val VISIBILITY_FILTERS = listOf("public" to "Public", "private" to "Private")

/** Raw VRChat unity-package platform codes the filter chips offer. */
private val PLATFORM_FILTERS = listOf("standalonewindows", "android")
