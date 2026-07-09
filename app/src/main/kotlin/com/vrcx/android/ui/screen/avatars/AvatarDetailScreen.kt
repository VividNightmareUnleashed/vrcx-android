package com.vrcx.android.ui.screen.avatars

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.vrcx.android.ui.components.ErrorState
import com.vrcx.android.ui.components.LoadingState
import com.vrcx.android.ui.components.SectionHeader
import com.vrcx.android.ui.components.VrcxCard
import com.vrcx.android.ui.components.VrcxDetailTopBar
import com.vrcx.android.ui.theme.LocalWallpaperActive

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AvatarDetailScreen(
    viewModel: AvatarDetailViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
    onUserClick: (String) -> Unit = {},
) {
    val avatar by viewModel.avatar.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val isFavorited by viewModel.isFavorited.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let { viewModel.clearMessage(); snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = if (LocalWallpaperActive.current) Color.Transparent else MaterialTheme.colorScheme.background,
    ) { _ ->
        Column(Modifier.fillMaxSize()) {
            VrcxDetailTopBar(
                title = avatar?.name ?: "Avatar",
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

            when {
                isLoading && avatar == null -> LoadingState()
                error != null && avatar == null -> ErrorState(error ?: "Error", onRetry = { viewModel.loadAvatar() })
                avatar != null -> {
                    val a = avatar!!
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
                            VrcxCard(Modifier.padding(horizontal = 16.dp)) {
                                Column(Modifier.padding(16.dp)) {
                                    SectionHeader("Platforms")
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        platforms.distinctBy { it.first }.forEach { (platform, perf) ->
                                            val label = when (platform) {
                                                "standalonewindows" -> "PC"
                                                "android" -> "Quest"
                                                "ios" -> "iOS"
                                                else -> platform
                                            }
                                            val perfLabel = if (perf.isNotEmpty()) " ($perf)" else ""
                                            AssistChip(onClick = {}, label = { Text("$label$perfLabel") })
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }

                        // Tags
                        val displayTags = a.tags.filter { !it.startsWith("system_") && !it.startsWith("admin_") && !it.startsWith("author_tag") }
                        if (displayTags.isNotEmpty()) {
                            VrcxCard(Modifier.padding(horizontal = 16.dp)) {
                                Column(Modifier.padding(16.dp)) {
                                    SectionHeader("Tags")
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        displayTags.forEach { tag ->
                                            AssistChip(onClick = {}, label = { Text(tag, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                                        }
                                    }
                                }
                            }
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
    }
}
