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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.Share
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vrcx.android.data.api.model.Instance
import com.vrcx.android.ui.common.valueOrNull
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
            VrcxDetailTopBar(
                title = state.valueOrNull?.world?.name ?: "World",
                onBack = onBack,
            )
            Box(Modifier.fillMaxWidth().weight(1f)) {
                WorldDetailBody(
                    state = state,
                    onRetry = viewModel::loadWorld,
                    onUserClick = onUserClick,
                    onInstanceClick = { pendingInstance = it },
                )
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
                    text = instanceLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text =
                        "Android can't launch VRChat directly. Send yourself an invite from " +
                            "the headset, or copy the launch URL to use elsewhere.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ActionRow(Icons.Outlined.Mail, "Self Invite", onSelfInvite)
                ActionRow(Icons.Outlined.ContentCopy, "Copy launch URL", onCopyLaunchUrl)
                ActionRow(Icons.Outlined.Share, "Share launch URL", onShare)
                Text(
                    text = launchUrl,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun copyToClipboard(context: Context, label: String, value: String) {
    context.getSystemService<ClipboardManager>()
        ?.setPrimaryClip(ClipData.newPlainText(label, value))
}

private fun shareText(context: Context, value: String) {
    val intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, value)
        }
    context.startActivity(Intent.createChooser(intent, null))
}
