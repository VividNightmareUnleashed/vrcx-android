package com.vrcx.android.ui.screen.world

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
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WorldDetailScreen(
    viewModel: WorldDetailViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
    onUserClick: (String) -> Unit = {},
) {
    val world by viewModel.world.collectAsStateWithLifecycle()
    val instances by viewModel.instances.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        VrcxDetailTopBar(title = world?.name ?: "World", onBack = onBack)

        when {
            isLoading && world == null -> LoadingState()
            error != null && world == null -> ErrorState(error ?: "Error", onRetry = { viewModel.loadWorld() })
            world != null -> {
                val w = world!!
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 16.dp)
                ) {
                    // Banner image
                    AsyncImage(
                        model = w.imageUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)),
                        contentScale = ContentScale.Crop,
                    )

                    // Name + Author
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(w.name, style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "by ${w.authorName}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { if (w.authorId.isNotEmpty()) onUserClick(w.authorId) },
                        )
                    }

                    // Description
                    if (w.description.isNotEmpty()) {
                        VrcxCard(Modifier.padding(horizontal = 16.dp)) {
                            Text(
                                w.description,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    // Stats
                    VrcxCard(Modifier.padding(horizontal = 16.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SectionHeader("Stats")
                            StatRow(Icons.Outlined.Groups, "Capacity", "${w.occupants} / ${w.capacity}")
                            StatRow(Icons.Outlined.Favorite, "Favorites", "${w.favorites}")
                            StatRow(Icons.Outlined.Visibility, "Visits", "${w.visits}")
                            StatRow(Icons.Outlined.Public, "Status", w.releaseStatus)
                        }
                    }
                    Spacer(Modifier.height(8.dp))

                    // Platform support
                    val platforms = w.unityPackages.map { it.platform }.distinct().filter { it.isNotEmpty() }
                    if (platforms.isNotEmpty()) {
                        VrcxCard(Modifier.padding(horizontal = 16.dp)) {
                            Column(Modifier.padding(16.dp)) {
                                SectionHeader("Platforms")
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    platforms.forEach { platform ->
                                        val label = when (platform) {
                                            "standalonewindows" -> "PC"
                                            "android" -> "Quest"
                                            "ios" -> "iOS"
                                            else -> platform
                                        }
                                        AssistChip(onClick = {}, label = { Text(label) })
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    // Tags
                    val displayTags = w.tags.filter { !it.startsWith("system_") && !it.startsWith("admin_") && !it.startsWith("author_tag") }
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

                    // Instances
                    if (instances.isNotEmpty()) {
                        SectionHeader("Instances", Modifier.padding(horizontal = 16.dp))
                        Spacer(Modifier.height(4.dp))
                        instances.forEach { instance ->
                            VrcxCard(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                                Column(Modifier.padding(16.dp)) {
                                    Row {
                                        Text(
                                            instance.type.replaceFirstChar { it.uppercase() },
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            instance.region.uppercase(),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(
                                        "${instance.nUsers} / ${instance.capacity}",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
