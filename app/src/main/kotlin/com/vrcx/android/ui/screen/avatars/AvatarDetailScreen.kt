@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.vrcx.android.ui.screen.avatars

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.vrcx.android.data.api.model.Avatar
import com.vrcx.android.ui.common.LoadState
import com.vrcx.android.ui.common.displayableTags
import com.vrcx.android.ui.common.platformLabel
import com.vrcx.android.ui.common.valueOrNull
import com.vrcx.android.ui.components.ChipCard
import com.vrcx.android.ui.components.ErrorState
import com.vrcx.android.ui.components.LoadingState
import com.vrcx.android.ui.components.SectionHeader
import com.vrcx.android.ui.components.VrcxCard
import com.vrcx.android.ui.components.VrcxDetailTopBar

private const val AVATAR_BANNER_ASPECT_RATIO = 16f / 9f

@Composable
fun AvatarDetailScreen(
    viewModel: AvatarDetailViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
    onUserClick: (String) -> Unit = {},
) {
    val avatarState by viewModel.avatar.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val favoriteEntryId by viewModel.favoriteEntryId.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            AvatarDetailTopBar(
                title = avatarState.valueOrNull?.name ?: "Avatar",
                isFavorited = favoriteEntryId != null,
                onBack = onBack,
                onToggleFavorite = viewModel::toggleFavorite,
            )
            AvatarDetailBody(
                state = avatarState,
                onRetry = viewModel::loadAvatar,
                onUserClick = onUserClick,
                onSelectAvatar = viewModel::selectAvatar,
            )
        }
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun AvatarDetailTopBar(title: String, isFavorited: Boolean, onBack: () -> Unit, onToggleFavorite: () -> Unit) {
    VrcxDetailTopBar(
        title = title,
        onBack = onBack,
        actions = {
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector =
                        if (isFavorited) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = if (isFavorited) "Unfavorite" else "Favorite",
                    tint =
                        if (isFavorited) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                )
            }
        },
    )
}

@Composable
private fun AvatarDetailBody(
    state: LoadState<Avatar>,
    onRetry: () -> Unit,
    onUserClick: (String) -> Unit,
    onSelectAvatar: () -> Unit,
) {
    when (state) {
        LoadState.NotLoaded, LoadState.Loading -> LoadingState()

        is LoadState.Failed -> ErrorState(state.message, onRetry = onRetry)

        is LoadState.Loaded ->
            LoadedAvatarContent(
                avatar = state.value,
                onUserClick = onUserClick,
                onSelectAvatar = onSelectAvatar,
            )
    }
}

@Composable
private fun LoadedAvatarContent(avatar: Avatar, onUserClick: (String) -> Unit, onSelectAvatar: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp),
    ) {
        AsyncImage(
            model = avatar.imageUrl,
            contentDescription = null,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(AVATAR_BANNER_ASPECT_RATIO)
                    .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)),
            contentScale = ContentScale.Crop,
        )
        AvatarIdentity(avatar, onUserClick)
        AvatarDescription(avatar.description)
        AvatarDetails(avatar)
        AvatarPlatforms(avatar)
        AvatarTags(avatar.tags)
        FlowRow(
            Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(onClick = onSelectAvatar) {
                Text("Select Avatar")
            }
        }
    }
}

@Composable
private fun AvatarIdentity(avatar: Avatar, onUserClick: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(avatar.name, style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "by ${avatar.authorName}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier =
                Modifier.clickable {
                    if (avatar.authorId.isNotEmpty()) onUserClick(avatar.authorId)
                },
        )
    }
}

@Composable
private fun AvatarDescription(description: String) {
    if (description.isNotEmpty()) {
        VrcxCard(Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun AvatarDetails(avatar: Avatar) {
    VrcxCard(Modifier.padding(horizontal = 16.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SectionHeader("Details")
            AvatarDetailRow("Status", avatar.releaseStatus)
            AvatarDetailRow("Version", avatar.version.toString())
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun AvatarDetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AvatarPlatforms(avatar: Avatar) {
    val platforms =
        avatar.unityPackages.mapNotNull { unityPackage ->
            unityPackage.platform
                .takeIf(String::isNotEmpty)
                ?.let { it to unityPackage.performanceRating }
        }
    if (platforms.isNotEmpty()) {
        ChipCard(
            title = "Platforms",
            labels =
                platforms.distinctBy { it.first }.map { (platform, performance) ->
                    platformLabel(platform) +
                        if (performance.isNotEmpty()) " ($performance)" else ""
                },
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun AvatarTags(tags: List<String>) {
    val labels = displayableTags(tags)
    if (labels.isNotEmpty()) {
        ChipCard(
            title = "Tags",
            labels = labels,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(8.dp))
    }
}
