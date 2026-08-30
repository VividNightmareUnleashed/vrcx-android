package com.vrcx.android.ui.screen.world

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.vrcx.android.data.api.model.Instance
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorldDetailScreen(
    viewModel: WorldDetailViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
    onUserClick: (String) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingInstance by remember { mutableStateOf<Instance?>(null) }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            VrcxDetailTopBar(title = state.valueOrNull?.world?.name ?: "World", onBack = onBack)
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when (val loadState = state) {
                    LoadState.NotLoaded, LoadState.Loading -> LoadingState()

                    is LoadState.Failed -> ErrorState(loadState.message, onRetry = { viewModel.loadWorld() })

                    is LoadState.Loaded -> {
                        val w = loadState.value.world
                        val instances = loadState.value.instances
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(bottom = 16.dp),
                        ) {
                            // A refresh that failed over data already on screen: the
                            // world stays, and the failure is a line above it.
                            loadState.staleError?.let { staleError ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = staleError,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = { viewModel.loadWorld() }) { Text("Retry") }
                                }
                            }

                            AsyncImage(
                                model = w.imageUrl,
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(16f / 9f)
                                    .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)),
                                contentScale = ContentScale.Crop,
                            )

                            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Text(w.name, style = MaterialTheme.typography.headlineSmall)
                                Text(
                                    "by ${w.authorName}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable {
                                        if (w.authorId.isNotEmpty()) onUserClick(w.authorId)
                                    },
                                )
                            }

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

                            VrcxCard(Modifier.padding(horizontal = 16.dp)) {
                                Column(
                                    Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    SectionHeader("Stats")
                                    StatRow(Icons.Outlined.Groups, "Online", "${w.occupants}")
                                    StatRow(Icons.Outlined.Groups, "Capacity", "${w.capacity} per instance")
                                    StatRow(Icons.Outlined.Favorite, "Favorites", "${w.favorites}")
                                    StatRow(Icons.Outlined.Visibility, "Visits", "${w.visits}")
                                    StatRow(Icons.Outlined.Public, "Status", w.releaseStatus)
                                }
                            }
                            Spacer(Modifier.height(8.dp))

                            val platforms = w.unityPackages.map { it.platform }.distinct().filter { it.isNotEmpty() }
                            if (platforms.isNotEmpty()) {
                                ChipCard(
                                    title = "Platforms",
                                    labels = platforms.map { platformLabel(it) },
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                                Spacer(Modifier.height(8.dp))
                            }

                            val displayTags = displayableTags(w.tags)
                            if (displayTags.isNotEmpty()) {
                                ChipCard(
                                    title = "Tags",
                                    labels = displayTags,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                                Spacer(Modifier.height(8.dp))
                            }

                            if (instances.isNotEmpty()) {
                                SectionHeader("Instances", Modifier.padding(horizontal = 16.dp))
                                Spacer(Modifier.height(4.dp))
                                instances.forEach { instance ->
                                    VrcxCard(
                                        Modifier
                                            .padding(horizontal = 16.dp, vertical = 4.dp)
                                            .clickable { pendingInstance = instance },
                                    ) {
                                        Column(Modifier.padding(16.dp)) {
                                            Row {
                                                Text(
                                                    instanceTypeLabel(instance),
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
                                            Text(
                                                "Tap for join options",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }

    pendingInstance?.let { instance ->
        val launchUrl = viewModel.browserLaunchUrl(instance.instanceId)
        InstanceActionDialog(
            instanceId = instance.instanceId,
            instanceLabel = "${instanceTypeLabel(instance)} · ${instance.region.uppercase()}",
            launchUrl = launchUrl,
            onSelfInvite = {
                viewModel.selfInvite(instance.instanceId)
                pendingInstance = null
            },
            onCopyLaunchUrl = {
                copyToClipboard(context, "VRChat instance", launchUrl)
                pendingInstance = null
            },
            onShare = {
                shareText(context, launchUrl)
                pendingInstance = null
            },
            onDismiss = { pendingInstance = null },
        )
    }
}

private fun instanceTypeLabel(instance: Instance): String = instance.type.replaceFirstChar { it.uppercase() }

@Composable
private fun InstanceActionDialog(
    instanceId: String,
    instanceLabel: String,
    launchUrl: String,
    onSelfInvite: () -> Unit,
    onCopyLaunchUrl: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Join $instanceId") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    instanceLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "Android can't launch VRChat directly. Send yourself an invite from the headset, " +
                        "or copy the launch URL to use elsewhere.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ActionRow(Icons.Outlined.Mail, "Self Invite", onSelfInvite)
                ActionRow(Icons.Outlined.ContentCopy, "Copy launch URL", onCopyLaunchUrl)
                ActionRow(Icons.Outlined.Share, "Share launch URL", onShare)
                Text(
                    launchUrl,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun ActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun copyToClipboard(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService<ClipboardManager>() ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
}

private fun shareText(context: Context, value: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, value)
    }
    context.startActivity(Intent.createChooser(intent, null))
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
