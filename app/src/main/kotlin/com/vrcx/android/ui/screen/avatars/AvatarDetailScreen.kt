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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AvatarDetailScreen(
    viewModel: AvatarDetailViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
    onUserClick: (String) -> Unit = {},
) {
    val avatarState by viewModel.avatar.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val favoriteEntryId by viewModel.favoriteEntryId.collectAsStateWithLifecycle()
    val isFavorited = favoriteEntryId != null
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let { snackbarHostState.showSnackbar(it); viewModel.clearMessage() }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            VrcxDetailTopBar(
                title = avatarState.valueOrNull?.name ?: "Avatar",
                onBack = onBack,
                actions = {
                    IconButton(onClick = { viewModel.toggleFavorite() }) {
                        Icon(
                            if (isFavorited) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            contentDescription = if (isFavorited) "Unfavorite" else "Favorite",
                            tint = if (isFavorited) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
            )

            when (val loadState = avatarState) {
                LoadState.NotLoaded, LoadState.Loading -> LoadingState()
                is LoadState.Failed -> ErrorState(loadState.message, onRetry = { viewModel.loadAvatar() })
                is LoadState.Loaded -> {
                    val a = loadState.value
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 16.dp)
                    ) {
                        // Image
                        AsyncImage(
                            model = a.imageUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)),
                            contentScale = ContentScale.Crop,
                        )

                        // Name + Author
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(a.name, style = MaterialTheme.typography.headlineSmall)
                            Text(
                                "by ${a.authorName}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { if (a.authorId.isNotEmpty()) onUserClick(a.authorId) },
                            )
                        }

                        // Description
                        if (a.description.isNotEmpty()) {
                            VrcxCard(Modifier.padding(horizontal = 16.dp)) {
                                Text(a.description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
                            }
                            Spacer(Modifier.height(8.dp))
                        }

                        // Details
                        VrcxCard(Modifier.padding(horizontal = 16.dp)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                SectionHeader("Details")
                                Row(Modifier.fillMaxWidth()) {
                                    Text("Status", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    Text(a.releaseStatus, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Row(Modifier.fillMaxWidth()) {
                                    Text("Version", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    Text("${a.version}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))

                        // Platform support
                        val platforms = a.unityPackages.map { it.platform to it.performanceRating }.filter { it.first.isNotEmpty() }
                        if (platforms.isNotEmpty()) {
                            ChipCard(
                                title = "Platforms",
                                labels = platforms.distinctBy { it.first }.map { (platform, perf) ->
                                    platformLabel(platform) + if (perf.isNotEmpty()) " ($perf)" else ""
                                },
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            Spacer(Modifier.height(8.dp))
                        }

                        // Tags
                        val displayTags = displayableTags(a.tags)
                        if (displayTags.isNotEmpty()) {
                            ChipCard(
                                title = "Tags",
                                labels = displayTags,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            Spacer(Modifier.height(8.dp))
                        }

                        // Actions
                        FlowRow(
                            Modifier.padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilledTonalButton(onClick = { viewModel.selectAvatar() }) {
                                Text("Select Avatar")
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}
