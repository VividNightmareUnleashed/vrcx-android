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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
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
import com.vrcx.android.data.api.model.Avatar
import com.vrcx.android.data.api.model.VrcUser
import com.vrcx.android.data.model.FriendState
import com.vrcx.android.ui.components.EmptyState
import com.vrcx.android.ui.components.ErrorState
import com.vrcx.android.ui.components.LoadingState
import com.vrcx.android.ui.components.SectionHeader
import com.vrcx.android.ui.components.TrustRankBadge
import com.vrcx.android.ui.components.UserListItem
import com.vrcx.android.ui.components.VrcxCard
import com.vrcx.android.ui.components.VrcxDetailTopBar
import com.vrcx.android.ui.components.WorldListItem
import com.vrcx.android.ui.theme.LocalWallpaperActive

private object UserDetailTabs {
    const val INFO = 0
    const val MUTUALS = 1
    const val GROUPS = 2
    const val WORLDS = 3
    const val AVATARS = 4
}

/**
 * Destructive social actions on UserDetailScreen are gated behind a confirm
 * dialog so a stray tap can't unfriend or block someone without warning. Each
 * variant carries the verb and a short consequence string the dialog renders.
 */
internal sealed class UserDestructiveAction(val verb: String, val consequence: String) {
    data object Block : UserDestructiveAction(
        verb = "Block",
        consequence = "They won't be able to interact with you in-game and will be removed from your friends list.",
    )
    data object Mute : UserDestructiveAction(
        verb = "Mute",
        consequence = "You won't hear them speak in any instance.",
    )
    data object HideAvatar : UserDestructiveAction(
        verb = "Hide avatar",
        consequence = "You'll see a fallback avatar in their place.",
    )
    data object ShowAvatar : UserDestructiveAction(
        verb = "Show avatar",
        consequence = "Their custom avatar will load again.",
    )
    data object Unfriend : UserDestructiveAction(
        verb = "Unfriend",
        consequence = "You'll be removed from each other's friends lists.",
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun UserDetailScreen(
    viewModel: UserDetailViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
    onUserClick: (String) -> Unit = {},
    onWorldClick: (String) -> Unit = {},
    onGroupClick: (String) -> Unit = {},
    onAvatarClick: (String) -> Unit = {},
) {
    val user by viewModel.user.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    var pendingDestructiveAction by remember { mutableStateOf<UserDestructiveAction?>(null) }
    val message by viewModel.message.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()
    val mutualFriends by viewModel.mutualFriends.collectAsState()
    val userGroups by viewModel.userGroups.collectAsState()
    val userWorlds by viewModel.userWorlds.collectAsState()
    val userAvatars by viewModel.userAvatars.collectAsState()
    val isFavorited by viewModel.isFavorited.collectAsState()
    val memo by viewModel.memo.collectAsState()
    val note by viewModel.note.collectAsState()
    val notifyEnabled by viewModel.notifyEnabled.collectAsState()
    val isTabLoading by viewModel.isTabLoading.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let { snackbarHostState.showSnackbar(it); viewModel.clearMessage() }
    }

    pendingDestructiveAction?.let { action ->
        val targetName = user?.displayName ?: "this user"
        AlertDialog(
            onDismissRequest = { pendingDestructiveAction = null },
            title = { Text("${action.verb} $targetName?") },
            text = { Text(action.consequence) },
            confirmButton = {
                TextButton(onClick = {
                    when (action) {
                        UserDestructiveAction.Block -> viewModel.blockUser()
                        UserDestructiveAction.Mute -> viewModel.muteUser()
                        UserDestructiveAction.HideAvatar -> viewModel.hideAvatar()
                        UserDestructiveAction.ShowAvatar -> viewModel.showAvatar()
                        UserDestructiveAction.Unfriend -> viewModel.unfriend()
                    }
                    pendingDestructiveAction = null
                }) {
                    Text(action.verb, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDestructiveAction = null }) { Text("Cancel") }
            },
        )
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
                    Tab(
                        selected = selectedTab == UserDetailTabs.INFO,
                        onClick = { viewModel.selectTab(UserDetailTabs.INFO) },
                        text = { Text("Info") },
                    )
                    Tab(
                        selected = selectedTab == UserDetailTabs.MUTUALS,
                        onClick = { viewModel.selectTab(UserDetailTabs.MUTUALS) },
                        text = { Text("Mutuals") },
                    )
                    Tab(
                        selected = selectedTab == UserDetailTabs.GROUPS,
                        onClick = { viewModel.selectTab(UserDetailTabs.GROUPS) },
                        text = { Text("Groups") },
                    )
                    Tab(
                        selected = selectedTab == UserDetailTabs.WORLDS,
                        onClick = { viewModel.selectTab(UserDetailTabs.WORLDS) },
                        text = { Text("Worlds") },
                    )
                    Tab(
                        selected = selectedTab == UserDetailTabs.AVATARS,
                        onClick = { viewModel.selectTab(UserDetailTabs.AVATARS) },
                        text = { Text("Avatars") },
                    )
                }

                when (selectedTab) {
                    UserDetailTabs.INFO -> InfoTab(
                        u = u,
                        note = note,
                        memo = memo,
                        notifyEnabled = notifyEnabled,
                        viewModel = viewModel,
                        onWorldClick = onWorldClick,
                        onConfirmDestructive = { pendingDestructiveAction = it },
                    )
                    UserDetailTabs.MUTUALS -> {
                        if (isTabLoading && mutualFriends.isEmpty()) LoadingState()
                        else MutualFriendsTab(mutualFriends, onUserClick)
                    }
                    UserDetailTabs.GROUPS -> {
                        if (isTabLoading && userGroups.isEmpty()) LoadingState()
                        else GroupsTab(userGroups, onGroupClick)
                    }
                    UserDetailTabs.WORLDS -> {
                        if (isTabLoading && userWorlds.isEmpty()) LoadingState()
                        else WorldsTab(userWorlds, onWorldClick)
                    }
                    UserDetailTabs.AVATARS -> {
                        if (isTabLoading && userAvatars.isEmpty()) LoadingState()
                        else AvatarsTab(userAvatars, onAvatarClick)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InfoTab(
    u: VrcUser,
    note: String?,
    memo: String?,
    notifyEnabled: Boolean,
    viewModel: UserDetailViewModel,
    onWorldClick: (String) -> Unit,
    onConfirmDestructive: (UserDestructiveAction) -> Unit,
) {
    var showNoteDialog by remember { mutableStateOf(false) }
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
            }
        }
        Spacer(Modifier.height(12.dp))

        // VRChat note
        VrcxCard {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("VRChat Note", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(
                        note?.ifBlank { "No note set" } ?: "No note set",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (note.isNullOrBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(onClick = { showNoteDialog = true }) {
                    Icon(Icons.Outlined.Edit, contentDescription = "Edit VRChat note")
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
                FilledTonalButton(onClick = { viewModel.toggleNotify() }) {
                    Icon(
                        if (notifyEnabled) Icons.Outlined.NotificationsActive else Icons.Outlined.NotificationsOff,
                        null,
                        Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (notifyEnabled) "Notifications On" else "Notifications Off")
                }
                OutlinedButton(onClick = { onConfirmDestructive(UserDestructiveAction.Unfriend) }) {
                    Icon(Icons.Default.PersonRemove, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Unfriend")
                }
                OutlinedButton(onClick = { viewModel.sendBoop() }) {
                    Text("Boop")
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
            OutlinedButton(onClick = { onConfirmDestructive(UserDestructiveAction.Block) }) {
                Icon(Icons.Default.Block, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Block")
            }
            OutlinedButton(onClick = { onConfirmDestructive(UserDestructiveAction.Mute) }) {
                Icon(Icons.Default.VolumeOff, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Mute")
            }
            OutlinedButton(onClick = { onConfirmDestructive(UserDestructiveAction.ShowAvatar) }) {
                Icon(Icons.Outlined.Visibility, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Show Avatar")
            }
            OutlinedButton(onClick = { onConfirmDestructive(UserDestructiveAction.HideAvatar) }) {
                Icon(Icons.Outlined.VisibilityOff, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Hide Avatar")
            }
        }
    }

    if (showNoteDialog) {
        var noteText by remember { mutableStateOf(note ?: "") }
        AlertDialog(
            onDismissRequest = { showNoteDialog = false },
            title = { Text("Edit VRChat Note") },
            text = {
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 5,
                    placeholder = { Text("Write a note synced to your VRChat account...") },
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.saveNote(noteText); showNoteDialog = false }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showNoteDialog = false }) { Text("Cancel") }
            },
        )
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
private fun MutualFriendsTab(mutualFriends: List<VrcUser>, onUserClick: (String) -> Unit) {
    if (mutualFriends.isEmpty()) {
        EmptyState(message = "No mutual friends")
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            items(mutualFriends, key = { it.id }) { mutual ->
                UserListItem(
                    avatarUrl = mutual.profilePicOverride.ifEmpty { mutual.currentAvatarThumbnailImageUrl }.ifBlank { null },
                    displayName = mutual.displayName,
                    subtitle = mutual.statusDescription.ifBlank { mutual.status },
                    tags = mutual.tags,
                    status = mutual.status,
                    state = mutualFriendState(mutual),
                    onClick = { onUserClick(mutual.id) },
                )
            }
        }
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
private fun AvatarsTab(avatars: List<Avatar>, onAvatarClick: (String) -> Unit) {
    if (avatars.isEmpty()) {
        EmptyState(message = "No avatars")
    } else {
        LazyColumn(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(avatars, key = { it.id }) { avatar ->
                VrcxCard(onClick = { onAvatarClick(avatar.id) }) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = avatar.thumbnailImageUrl.ifEmpty { avatar.imageUrl },
                            contentDescription = null,
                            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)),
                            contentScale = ContentScale.Crop,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(avatar.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                avatar.releaseStatus.replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
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

private fun mutualFriendState(user: VrcUser): FriendState =
    when {
        user.location.isNullOrBlank() || user.location == "offline" -> FriendState.OFFLINE
        user.location == "private" || user.state.equals("active", ignoreCase = true) -> FriendState.ACTIVE
        else -> FriendState.ONLINE
    }
