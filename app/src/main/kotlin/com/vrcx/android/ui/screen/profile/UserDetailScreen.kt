package com.vrcx.android.ui.screen.profile

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.vrcx.android.data.api.model.VrcUser
import com.vrcx.android.data.model.FriendState
import com.vrcx.android.ui.components.SectionHeader
import com.vrcx.android.ui.components.TrustRankBadge
import com.vrcx.android.ui.components.VrcxCard
import com.vrcx.android.ui.components.VrcxDetailTopBar
import com.vrcx.android.ui.components.statusColor
import com.vrcx.android.ui.components.ErrorState
import com.vrcx.android.ui.components.LoadingState

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun UserDetailScreen(
    viewModel: UserDetailViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
) {
    val user by viewModel.user.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val message by viewModel.message.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = { VrcxDetailTopBar(title = user?.displayName ?: "User", onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (isLoading && user == null) {
            LoadingState(Modifier.padding(padding))
        } else if (user == null) {
            ErrorState("User not found", onRetry = { viewModel.loadUser() }, modifier = Modifier.padding(padding))
        } else {
            val u = user!!
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                ProfileHeader(u)
                Spacer(Modifier.height(16.dp))

                if (u.bio.isNotBlank()) {
                    VrcxCard {
                        Column(Modifier.padding(16.dp)) {
                            Text("Bio", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(4.dp))
                            Text(u.bio, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                if (!u.location.isNullOrEmpty() && u.location != "offline" && u.location != "private") {
                    VrcxCard {
                        Column(Modifier.padding(16.dp)) {
                            Text("Location", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(4.dp))
                            Text(u.location ?: "", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                VrcxCard {
                    Column(Modifier.padding(16.dp)) {
                        Text("Info", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        InfoRow("Platform", u.lastPlatform)
                        if (u.dateJoined.isNotEmpty()) InfoRow("Joined", u.dateJoined)
                        if (u.lastLogin.isNotEmpty()) InfoRow("Last Login", u.lastLogin.take(10))
                        if (!u.note.isNullOrBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text("Note", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(u.note ?: "", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))

                Text("Actions", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (u.isFriend) {
                        OutlinedButton(onClick = { viewModel.unfriend() }) {
                            Icon(Icons.Default.PersonRemove, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Unfriend")
                        }
                    } else {
                        FilledTonalButton(onClick = { viewModel.sendFriendRequest() }) {
                            Icon(Icons.Default.PersonAdd, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Add Friend")
                        }
                    }
                    OutlinedButton(onClick = { viewModel.blockUser() }) {
                        Icon(Icons.Default.Block, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Block")
                    }
                    OutlinedButton(onClick = { viewModel.muteUser() }) {
                        Icon(Icons.Default.VolumeOff, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Mute")
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileHeader(user: VrcUser) {
    val imageUrl = user.profilePicOverride.ifEmpty { user.currentAvatarThumbnailImageUrl }
    val state = when (user.state) {
        "online" -> FriendState.ONLINE
        "active" -> FriendState.ACTIVE
        else -> FriendState.OFFLINE
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        AsyncImage(
            model = imageUrl,
            contentDescription = null,
            modifier = Modifier.size(96.dp).clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.height(12.dp))
        Text(user.displayName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (user.pronouns.isNotBlank()) {
            Text(user.pronouns, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TrustRankBadge(tags = user.tags)
            AssistChip(
                onClick = {},
                label = { Text(user.state.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall) },
            )
        }
        if (user.statusDescription.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "${user.status}: ${user.statusDescription}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(100.dp))
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
