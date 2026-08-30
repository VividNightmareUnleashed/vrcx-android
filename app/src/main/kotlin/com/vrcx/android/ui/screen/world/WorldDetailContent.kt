package com.vrcx.android.ui.screen.world

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.vrcx.android.data.api.model.Instance
import com.vrcx.android.data.api.model.World
import com.vrcx.android.ui.common.LoadState
import com.vrcx.android.ui.common.displayableTags
import com.vrcx.android.ui.common.platformLabel
import com.vrcx.android.ui.components.ChipCard
import com.vrcx.android.ui.components.ErrorState
import com.vrcx.android.ui.components.LoadingState
import com.vrcx.android.ui.components.SectionHeader
import com.vrcx.android.ui.components.VrcxCard

private const val WORLD_BANNER_ASPECT_RATIO = 16f / 9f

@Composable
internal fun WorldDetailBody(
    state: LoadState<WorldDetailData>,
    onRetry: () -> Unit,
    onUserClick: (String) -> Unit,
    onInstanceClick: (Instance) -> Unit,
) {
    when (state) {
        LoadState.NotLoaded, LoadState.Loading -> LoadingState()

        is LoadState.Failed -> ErrorState(state.message, onRetry = onRetry)

        is LoadState.Loaded ->
            LoadedWorldContent(state, onRetry, onUserClick, onInstanceClick)
    }
}

@Composable
private fun LoadedWorldContent(
    loaded: LoadState.Loaded<WorldDetailData>,
    onRetry: () -> Unit,
    onUserClick: (String) -> Unit,
    onInstanceClick: (Instance) -> Unit,
) {
    val world = loaded.value.world
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp),
    ) {
        loaded.staleError?.let { WorldStaleError(it, onRetry) }
        WorldSummary(world, onUserClick)
        WorldStats(world)
        WorldMetadata(world)
        if (loaded.value.instances.isNotEmpty()) {
            WorldInstances(loaded.value.instances, onInstanceClick)
        }
    }
}

@Composable
private fun WorldStaleError(message: String, onRetry: () -> Unit) {
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
private fun WorldSummary(world: World, onUserClick: (String) -> Unit) {
    AsyncImage(
        model = world.imageUrl,
        contentDescription = null,
        modifier =
            Modifier
                .fillMaxWidth()
                .aspectRatio(WORLD_BANNER_ASPECT_RATIO)
                .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)),
        contentScale = ContentScale.Crop,
    )
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(world.name, style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "by ${world.authorName}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier =
                Modifier.clickable {
                    if (world.authorId.isNotEmpty()) onUserClick(world.authorId)
                },
        )
    }
    if (world.description.isNotEmpty()) {
        VrcxCard(Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = world.description,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun WorldStats(world: World) {
    VrcxCard(Modifier.padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader("Stats")
            StatRow(Icons.Outlined.Groups, "Online", world.occupants.toString())
            StatRow(Icons.Outlined.Groups, "Capacity", "${world.capacity} per instance")
            StatRow(Icons.Outlined.Favorite, "Favorites", world.favorites.toString())
            StatRow(Icons.Outlined.Visibility, "Visits", world.visits.toString())
            StatRow(Icons.Outlined.Public, "Status", world.releaseStatus)
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun WorldMetadata(world: World) {
    val platforms =
        world.unityPackages
            .map { it.platform }
            .filter(String::isNotEmpty)
            .distinct()
    if (platforms.isNotEmpty()) {
        ChipCard(
            title = "Platforms",
            labels = platforms.map(::platformLabel),
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(8.dp))
    }
    val tags = displayableTags(world.tags)
    if (tags.isNotEmpty()) {
        ChipCard(
            title = "Tags",
            labels = tags,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun WorldInstances(instances: List<Instance>, onInstanceClick: (Instance) -> Unit) {
    SectionHeader("Instances", Modifier.padding(horizontal = 16.dp))
    Spacer(Modifier.height(4.dp))
    instances.forEach { instance ->
        VrcxCard(
            Modifier
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .clickable { onInstanceClick(instance) },
        ) {
            Column(Modifier.padding(16.dp)) {
                Row {
                    Text(
                        text = instanceTypeLabel(instance),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = instance.region.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "${instance.nUsers} / ${instance.capacity}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Tap for join options",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatRow(icon: ImageVector, label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal fun instanceTypeLabel(instance: Instance): String = instance.type.replaceFirstChar { it.uppercase() }
