package com.vrcx.android.ui.screen.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.vrcx.android.data.api.model.VrcUser
import com.vrcx.android.data.model.FriendState
import com.vrcx.android.ui.components.EmptyState
import com.vrcx.android.ui.components.ErrorState
import com.vrcx.android.ui.components.LoadingState
import com.vrcx.android.ui.components.SectionHeader
import com.vrcx.android.ui.components.TrustRankBadge
import com.vrcx.android.ui.components.VrcxCard
import com.vrcx.android.ui.components.VrcxDetailTopBar
import com.vrcx.android.ui.components.WorldListItem
import com.vrcx.android.ui.theme.LocalWallpaperActive

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun UserDetailScreen(
    viewModel: UserDetailViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
    onWorldClick: (String) -> Unit = {},
    onGroupClick: (String) -> Unit = {},
) {
    val user by viewModel.user.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val message by viewModel.message.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()
    val userGroups by viewModel.userGroups.collectAsState()
    val userWorlds by viewModel.userWorlds.collectAsState()
    val isFavorited by viewModel.isFavorited.collectAsState()
    val memo by viewModel.memo.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let { snackbarHostState.showSnackbar(it); viewModel.clearMessage() }
    }

    val isWallpaperActive = LocalWallpaperActive.current
    Scaffold(
        containerColor = if (isWallpaperActive) Color.Transparent else MaterialTheme.colorScheme.background,
        topBar = {
            VrcxDetailTopBar(
                title = user?.displayName ?: "User",
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
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (isLoading && user == null) {
            LoadingState(Modifier.padding(padding))
        } else if (user == null) {
            ErrorState("User not found", onRetry = { viewModel.loadUser() }, modifier = Modifier.padding(padding))
        } else {
            val u = user!!
            Column(Modifier.fillMaxSize().padding(padding)) {
                // Profile header (always visible)
                ProfileHeader(u)
                Spacer(Modifier.height(8.dp))

                // Tabs
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(selected = selectedTab == 0, onClick = { viewModel.selectTab(0) }, text = { Text("Info") })
                    Tab(selected = selectedTab == 1, onClick = { viewModel.selectTab(1) }, text = { Text("Groups") })
                    Tab(selected = selectedTab == 2, onClick = { viewModel.selectTab(2) }, text = { Text("Worlds") })
                }

                when (selectedTab) {
                    0 -> InfoTab(u, memo, viewModel, onWorldClick)
                    1 -> GroupsTab(userGroups, onGroupClick)
                    2 -> WorldsTab(userWorlds, onWorldClick)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InfoTab(
    u: VrcUser,
    memo: String?,
    viewModel: UserDetailViewModel,
    onWorldClick: (String) -> Unit,
) {
    var showMemoDialog by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        // Bio
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

        // Location
        if (!u.location.isNullOrEmpty() && u.location != "offline" && u.location != "private") {
            VrcxCard(onClick = { onWorldClick(u.location!!.substringBefore(":")) }) {
                Column(Modifier.padding(16.dp)) {
                    Text("Location", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    Text(u.location ?: "", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // Info card
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
        Spacer(Modifier.height(12.dp))

        // Memo
        VrcxCard {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Memo", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(
                        memo?.ifBlank { "No memo set" } ?: "No memo set",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (memo.isNullOrBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(onClick = { showMemoDialog = true }) {
                    Icon(Icons.Outlined.Edit, contentDescription = "Edit memo")
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        // Actions
        Text("Actions", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (u.isFriend) {
                OutlinedButton(onClick = { viewModel.unfriend() }) {
                    Icon(Icons.Default.PersonRemove, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Unfriend")
                }
            } else {
                FilledTonalButton(onClick = { viewModel.sendFriendRequest() }) {
                    Icon(Icons.Default.PersonAdd, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Add Friend")
                }
            }
            FilledTonalButton(onClick = { viewModel.sendInvite() }) {
                Icon(Icons.Default.Send, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Invite")
            }
            OutlinedButton(onClick = { viewModel.requestInvite() }) {
                Text("Request Invite")
            }
            OutlinedButton(onClick = { viewModel.blockUser() }) {
                Icon(Icons.Default.Block, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Block")
            }
            OutlinedButton(onClick = { viewModel.muteUser() }) {
                Icon(Icons.Default.VolumeOff, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Mute")
            }
            OutlinedButton(onClick = { viewModel.showAvatar() }) {
                Icon(Icons.Outlined.Visibility, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Show Avatar")
            }
            OutlinedButton(onClick = { viewModel.hideAvatar() }) {
                Icon(Icons.Outlined.VisibilityOff, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Hide Avatar")
            }
        }
    }

    // Memo edit dialog
    if (showMemoDialog) {
        var memoText by remember { mutableStateOf(memo ?: "") }
        AlertDialog(
            onDismissRequest = { showMemoDialog = false },
            title = { Text("Edit Memo") },
            text = {
                OutlinedTextField(
                    value = memoText,
                    onValueChange = { memoText = it },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 5,
                    placeholder = { Text("Write a memo about this user...") },
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.saveMemo(memoText); showMemoDialog = false }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showMemoDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun GroupsTab(groups: List<com.vrcx.android.data.api.model.Group>, onGroupClick: (String) -> Unit) {
    if (groups.isEmpty()) {
        EmptyState(message = "No groups")
    } else {
        LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(groups, key = { it.id }) { group ->
                val groupId = group.groupId.ifEmpty { group.id }
                VrcxCard(onClick = { onGroupClick(groupId) }) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (group.iconUrl.isNotEmpty()) {
                            AsyncImage(
                                model = group.iconUrl,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp).clip(CircleShape),
                                contentScale = ContentScale.Crop,
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                        Column {
                            Text(group.name, style = MaterialTheme.typography.bodyMedium)
                            Text("${group.memberCount} members", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorldsTab(worlds: List<com.vrcx.android.data.api.model.World>, onWorldClick: (String) -> Unit) {
    if (worlds.isEmpty()) {
        EmptyState(message = "No worlds")
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            items(worlds, key = { it.id }) { world ->
                WorldListItem(
                    thumbnailUrl = world.thumbnailImageUrl,
                    name = world.name,
                    authorName = world.authorName,
                    occupants = world.occupants,
                    onClick = { onWorldClick(world.id) },
                )
            }
        }
    }
}

@Composable
private fun ProfileHeader(user: VrcUser) {
    val imageUrl = user.profilePicOverride.ifEmpty { user.currentAvatarThumbnailImageUrl }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
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
            AssistChip(onClick = {}, label = { Text(user.state.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall) })
        }
        if (user.statusDescription.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text("${user.status}: ${user.statusDescription}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
