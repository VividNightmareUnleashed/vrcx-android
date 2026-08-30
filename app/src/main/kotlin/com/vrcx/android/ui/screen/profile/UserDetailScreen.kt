package com.vrcx.android.ui.screen.profile

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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.vrcx.android.data.api.model.Avatar
import com.vrcx.android.data.api.model.VrcUser
import com.vrcx.android.data.api.model.displayAvatarUrl
import com.vrcx.android.data.model.friendStateOf
import com.vrcx.android.data.model.resolvedWorldId
import com.vrcx.android.data.repository.FavoriteWorldSection
import com.vrcx.android.data.repository.canonicalGroupId
import com.vrcx.android.ui.common.prettyVisibility
import com.vrcx.android.ui.components.ConfirmDialog
import com.vrcx.android.ui.components.EmptyState
import com.vrcx.android.ui.components.ErrorState
import com.vrcx.android.ui.components.LoadingState
import com.vrcx.android.ui.components.SectionHeader
import com.vrcx.android.ui.components.TrustRankBadge
import com.vrcx.android.ui.components.UserAvatar
import com.vrcx.android.ui.components.UserListItem
import com.vrcx.android.ui.components.VrcxCard
import com.vrcx.android.ui.components.VrcxDetailTopBar
import com.vrcx.android.ui.components.VrcxTabRow
import com.vrcx.android.ui.components.WorldListItem
import com.vrcx.android.ui.theme.LocalWallpaperActive

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

private enum class UserDetailEditor {
    NOTE,
    MEMO,
}

private sealed interface UserDetailAction {
    data object Reload : UserDetailAction
    data object ToggleFavorite : UserDetailAction
    data class SelectTab(val tab: UserDetailTab) : UserDetailAction
    data class SelectFavoriteWorldGroup(val tag: String) : UserDetailAction
    data object EditNote : UserDetailAction
    data object EditMemo : UserDetailAction
    data object ToggleNotify : UserDetailAction
    data object SendBoop : UserDetailAction
    data object SendFriendRequest : UserDetailAction
    data object CancelFriendRequest : UserDetailAction
    data object SendInvite : UserDetailAction
    data object RequestInvite : UserDetailAction
    data class RequestDestructiveConfirmation(val action: UserDestructiveAction) : UserDetailAction
}

private sealed interface UserDetailDestination {
    data class User(val id: String) : UserDetailDestination
    data class World(val id: String) : UserDetailDestination
    data class Group(val id: String) : UserDetailDestination
    data class Avatar(val id: String) : UserDetailDestination
}

private fun UserDetailViewModel.handleUiAction(
    action: UserDetailAction,
    onEdit: (UserDetailEditor) -> Unit,
    onRequestDestructiveConfirmation: (UserDestructiveAction) -> Unit,
) {
    when (action) {
        UserDetailAction.Reload -> loadUser()
        UserDetailAction.ToggleFavorite -> toggleFavorite()
        is UserDetailAction.SelectTab -> selectTab(action.tab)
        is UserDetailAction.SelectFavoriteWorldGroup -> selectFavoriteWorldGroup(action.tag)
        UserDetailAction.EditNote -> onEdit(UserDetailEditor.NOTE)
        UserDetailAction.EditMemo -> onEdit(UserDetailEditor.MEMO)
        UserDetailAction.ToggleNotify -> toggleNotify()
        UserDetailAction.SendBoop -> sendBoop()
        UserDetailAction.SendFriendRequest -> sendFriendRequest()
        UserDetailAction.CancelFriendRequest -> cancelFriendRequest()
        UserDetailAction.SendInvite -> sendInvite()
        UserDetailAction.RequestInvite -> requestInvite()
        is UserDetailAction.RequestDestructiveConfirmation -> onRequestDestructiveConfirmation(action.action)
    }
}

private fun UserDetailViewModel.performDestructiveAction(action: UserDestructiveAction) {
    when (action) {
        UserDestructiveAction.Block -> blockUser()
        UserDestructiveAction.Mute -> muteUser()
        UserDestructiveAction.HideAvatar -> hideAvatar()
        UserDestructiveAction.ShowAvatar -> showAvatar()
        UserDestructiveAction.Unfriend -> unfriend()
    }
}

private fun UserDetailDestination.navigate(
    onUserClick: (String) -> Unit,
    onWorldClick: (String) -> Unit,
    onGroupClick: (String) -> Unit,
    onAvatarClick: (String) -> Unit,
) {
    when (this) {
        is UserDetailDestination.User -> onUserClick(id)
        is UserDetailDestination.World -> onWorldClick(id)
        is UserDetailDestination.Group -> onGroupClick(id)
        is UserDetailDestination.Avatar -> onAvatarClick(id)
    }
}

@Composable
fun UserDetailScreen(
    viewModel: UserDetailViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
    onUserClick: (String) -> Unit = {},
    onWorldClick: (String) -> Unit = {},
    onGroupClick: (String) -> Unit = {},
    onAvatarClick: (String) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var pendingDestructiveAction by remember { mutableStateOf<UserDestructiveAction?>(null) }
    var activeEditor by rememberSaveable { mutableStateOf<UserDetailEditor?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    pendingDestructiveAction?.let { action ->
        UserDestructiveActionDialog(
            action = action,
            targetName = state.user?.displayName ?: "this user",
            onConfirm = {
                viewModel.performDestructiveAction(action)
                pendingDestructiveAction = null
            },
            onDismiss = { pendingDestructiveAction = null },
        )
    }

    activeEditor?.let { editor ->
        UserDetailTextEditorDialog(
            editor = editor,
            initialText = if (editor == UserDetailEditor.NOTE) state.note.orEmpty() else state.memo.orEmpty(),
            onSave = { text ->
                if (editor == UserDetailEditor.NOTE) viewModel.saveNote(text) else viewModel.saveMemo(text)
                activeEditor = null
            },
            onDismiss = { activeEditor = null },
        )
    }

    UserDetailContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onAction = { action ->
            viewModel.handleUiAction(
                action = action,
                onEdit = { activeEditor = it },
                onRequestDestructiveConfirmation = { pendingDestructiveAction = it },
            )
        },
        onNavigate = { it.navigate(onUserClick, onWorldClick, onGroupClick, onAvatarClick) },
    )
}

@Composable
private fun UserDestructiveActionDialog(
    action: UserDestructiveAction,
    targetName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmDialog(
        title = "${action.verb} $targetName?",
        message = action.consequence,
        confirmLabel = action.verb,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

@Composable
private fun UserDetailContent(
    state: UserDetailUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onAction: (UserDetailAction) -> Unit,
    onNavigate: (UserDetailDestination) -> Unit,
) {
    val user = state.user
    val isWallpaperActive = LocalWallpaperActive.current
    Scaffold(
        containerColor = if (isWallpaperActive) Color.Transparent else MaterialTheme.colorScheme.background,
        topBar = {
            VrcxDetailTopBar(
                title = user?.displayName ?: "User",
                onBack = onBack,
                actions = {
                    if (!state.isSelf) {
                        IconButton(onClick = { onAction(UserDetailAction.ToggleFavorite) }) {
                            Icon(
                                if (state.isFavorited) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = if (state.isFavorited) "Unfavorite" else "Favorite",
                                tint = if (state.isFavorited) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (state.isLoading && user == null) {
            LoadingState(Modifier.padding(padding))
        } else if (user == null) {
            ErrorState(
                "User not found",
                onRetry = { onAction(UserDetailAction.Reload) },
                modifier = Modifier.padding(padding),
            )
        } else {
            LoadedUserDetailContent(
                state = state,
                user = user,
                modifier = Modifier.padding(padding),
                onAction = onAction,
                onNavigate = onNavigate,
            )
        }
    }
}

@Composable
private fun LoadedUserDetailContent(
    state: UserDetailUiState,
    user: VrcUser,
    modifier: Modifier,
    onAction: (UserDetailAction) -> Unit,
    onNavigate: (UserDetailDestination) -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        ProfileHeader(user)
        Spacer(Modifier.height(8.dp))

        VrcxTabRow(selectedTabIndex = state.selectedTab.ordinal) {
            UserDetailTab.entries.forEach { tab ->
                Tab(
                    selected = state.selectedTab == tab,
                    onClick = { onAction(UserDetailAction.SelectTab(tab)) },
                    text = { Text(tab.label) },
                )
            }
        }

        UserDetailTabContent(
            state = state,
            user = user,
            onAction = onAction,
            onNavigate = onNavigate,
        )
    }
}

@Composable
private fun UserDetailTabContent(
    state: UserDetailUiState,
    user: VrcUser,
    onAction: (UserDetailAction) -> Unit,
    onNavigate: (UserDetailDestination) -> Unit,
) {
    // Keep cached rows visible when a previously loaded tab is selected again.
    val isTabLoading = state.selectedTab in state.loadingTabs && state.selectedTab !in state.loadedTabs
    when (state.selectedTab) {
        UserDetailTab.INFO -> InfoTab(
            state = state,
            user = user,
            onAction = onAction,
            onWorldClick = { onNavigate(UserDetailDestination.World(it)) },
        )

        UserDetailTab.MUTUALS ->
            if (isTabLoading) {
                LoadingState()
            } else {
                MutualFriendsTab(state.mutualFriends) { onNavigate(UserDetailDestination.User(it)) }
            }

        UserDetailTab.GROUPS ->
            if (isTabLoading) {
                LoadingState()
            } else {
                GroupsTab(state.userGroups) { onNavigate(UserDetailDestination.Group(it)) }
            }

        UserDetailTab.WORLDS ->
            if (isTabLoading) {
                LoadingState()
            } else {
                WorldsTab(
                    worlds = state.userWorlds,
                    onWorldClick = { onNavigate(UserDetailDestination.World(it)) },
                )
            }

        UserDetailTab.AVATARS ->
            if (isTabLoading) {
                LoadingState()
            } else {
                AvatarsTab(state.userAvatars) { onNavigate(UserDetailDestination.Avatar(it)) }
            }

        UserDetailTab.FAVORITE_WORLDS ->
            if (isTabLoading) {
                LoadingState()
            } else {
                FavoriteWorldsTab(
                    sections = state.favoriteWorldSections,
                    selectedTag = state.selectedFavoriteWorldTag,
                    onSelectGroup = { onAction(UserDetailAction.SelectFavoriteWorldGroup(it)) },
                    onWorldClick = { onNavigate(UserDetailDestination.World(it)) },
                )
            }
    }
}

@Composable
private fun InfoTab(
    state: UserDetailUiState,
    user: VrcUser,
    onAction: (UserDetailAction) -> Unit,
    onWorldClick: (String) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        if (user.bio.isNotBlank()) {
            UserBioCard(user.bio)
            Spacer(Modifier.height(12.dp))
        }

        resolvedWorldId(user.location, user.travelingToLocation)?.let { worldId ->
            UserLocationCard(user.location.orEmpty(), onClick = { onWorldClick(worldId) })
            Spacer(Modifier.height(12.dp))
        }

        UserProfileInfoCard(user)
        Spacer(Modifier.height(12.dp))

        EditableProfileTextCard(
            title = "VRChat Note",
            text = state.note,
            emptyLabel = "No note set",
            editContentDescription = "Edit VRChat note",
            onEdit = { onAction(UserDetailAction.EditNote) },
        )
        Spacer(Modifier.height(12.dp))

        EditableProfileTextCard(
            title = "Memo",
            text = state.memo,
            emptyLabel = "No memo set",
            editContentDescription = "Edit memo",
            onEdit = { onAction(UserDetailAction.EditMemo) },
        )
        Spacer(Modifier.height(16.dp))

        if (!state.isSelf) {
            UserSocialActions(user.isFriend, state.notifyEnabled, onAction)
        }
    }
}

@Composable
private fun UserBioCard(bio: String) {
    VrcxCard {
        Column(Modifier.padding(16.dp)) {
            Text("Bio", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text(bio, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun UserLocationCard(location: String, onClick: () -> Unit) {
    VrcxCard(onClick = onClick) {
        Column(Modifier.padding(16.dp)) {
            Text("Location", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text(location, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun UserProfileInfoCard(user: VrcUser) {
    VrcxCard {
        Column(Modifier.padding(16.dp)) {
            Text("Info", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            InfoRow("Platform", user.lastPlatform)
            if (user.dateJoined.isNotEmpty()) InfoRow("Joined", user.dateJoined)
            if (user.lastLogin.isNotEmpty()) InfoRow("Last Login", user.lastLogin.take(10))
        }
    }
}

@Composable
private fun EditableProfileTextCard(
    title: String,
    text: String?,
    emptyLabel: String,
    editContentDescription: String,
    onEdit: () -> Unit,
) {
    VrcxCard {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(
                    text.orEmpty().ifBlank { emptyLabel },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (text.isNullOrBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Outlined.Edit, contentDescription = editContentDescription)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UserSocialActions(isFriend: Boolean, notifyEnabled: Boolean, onAction: (UserDetailAction) -> Unit) {
    Text("Actions", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(8.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RelationshipActions(isFriend, notifyEnabled, onAction)
        FilledTonalButton(onClick = { onAction(UserDetailAction.SendInvite) }) {
            Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Invite")
        }
        OutlinedButton(onClick = { onAction(UserDetailAction.RequestInvite) }) {
            Text("Request Invite")
        }
        SafetyActions(onAction)
    }
}

@Composable
private fun RelationshipActions(isFriend: Boolean, notifyEnabled: Boolean, onAction: (UserDetailAction) -> Unit) {
    if (isFriend) {
        FilledTonalButton(onClick = { onAction(UserDetailAction.ToggleNotify) }) {
            Icon(
                if (notifyEnabled) Icons.Outlined.NotificationsActive else Icons.Outlined.NotificationsOff,
                null,
                Modifier.size(18.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(if (notifyEnabled) "Notifications On" else "Notifications Off")
        }
        OutlinedButton(
            onClick = {
                onAction(UserDetailAction.RequestDestructiveConfirmation(UserDestructiveAction.Unfriend))
            },
        ) {
            Icon(Icons.Default.PersonRemove, null, Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Unfriend")
        }
        OutlinedButton(onClick = { onAction(UserDetailAction.SendBoop) }) {
            Text("Boop")
        }
    } else {
        FilledTonalButton(onClick = { onAction(UserDetailAction.SendFriendRequest) }) {
            Icon(Icons.Default.PersonAdd, null, Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Add Friend")
        }
        // VRChat does not expose outbound friend-request status, so cancellation
        // remains available and a missing request harmlessly produces a 404.
        OutlinedButton(onClick = { onAction(UserDetailAction.CancelFriendRequest) }) {
            Text("Cancel Pending Request")
        }
    }
}

@Composable
private fun SafetyActions(onAction: (UserDetailAction) -> Unit) {
    OutlinedButton(
        onClick = { onAction(UserDetailAction.RequestDestructiveConfirmation(UserDestructiveAction.Block)) },
    ) {
        Icon(Icons.Default.Block, null, Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text("Block")
    }
    OutlinedButton(
        onClick = { onAction(UserDetailAction.RequestDestructiveConfirmation(UserDestructiveAction.Mute)) },
    ) {
        Icon(Icons.AutoMirrored.Filled.VolumeOff, null, Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text("Mute")
    }
    OutlinedButton(
        onClick = { onAction(UserDetailAction.RequestDestructiveConfirmation(UserDestructiveAction.ShowAvatar)) },
    ) {
        Icon(Icons.Outlined.Visibility, null, Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text("Show Avatar")
    }
    OutlinedButton(
        onClick = { onAction(UserDetailAction.RequestDestructiveConfirmation(UserDestructiveAction.HideAvatar)) },
    ) {
        Icon(Icons.Outlined.VisibilityOff, null, Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text("Hide Avatar")
    }
}

@Composable
private fun UserDetailTextEditorDialog(
    editor: UserDetailEditor,
    initialText: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Re-seed after a profile refresh while retaining an in-progress edit across rotation.
    var text by rememberSaveable(editor, initialText) { mutableStateOf(initialText) }
    val isNote = editor == UserDetailEditor.NOTE
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNote) "Edit VRChat Note" else "Edit Memo") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 5,
                placeholder = {
                    Text(
                        if (isNote) {
                            "Write a note synced to your VRChat account..."
                        } else {
                            "Write a memo about this user..."
                        },
                    )
                },
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun MutualFriendsTab(mutualFriends: List<VrcUser>, onUserClick: (String) -> Unit) {
    if (mutualFriends.isEmpty()) {
        EmptyState(message = "No mutual friends")
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            items(mutualFriends, key = { it.id }) { mutual ->
                UserListItem(
                    avatarUrl = mutual.displayAvatarUrl().ifBlank { null },
                    displayName = mutual.displayName,
                    subtitle = mutual.statusDescription.ifBlank { mutual.status },
                    tags = mutual.tags,
                    status = mutual.status,
                    state = friendStateOf(mutual.location),
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
                val groupId = group.canonicalGroupId()
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
                            Text(
                                "${group.memberCount} members",
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
private fun WorldsTab(
    worlds: List<com.vrcx.android.data.api.model.World>,
    onWorldClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (worlds.isEmpty()) {
        EmptyState(message = "No worlds")
    } else {
        LazyColumn(modifier.fillMaxSize()) {
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FavoriteWorldsTab(
    sections: List<FavoriteWorldSection>,
    selectedTag: String?,
    onSelectGroup: (String) -> Unit,
    onWorldClick: (String) -> Unit,
) {
    if (sections.isEmpty()) {
        EmptyState(message = "No public favorite worlds")
        return
    }

    val selectedSection = sections.firstOrNull { it.tag == selectedTag } ?: sections.first()

    Column(Modifier.fillMaxSize()) {
        SectionHeader(title = "Favorite World Groups")
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            sections.forEach { section ->
                FilterChip(
                    selected = section.tag == selectedSection.tag,
                    onClick = { onSelectGroup(section.tag) },
                    label = { Text("${section.displayName} (${section.worlds.size})") },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = buildString {
                append(selectedSection.displayName)
                if (selectedSection.visibility.isNotBlank()) {
                    append(" • ")
                    append(selectedSection.visibility.prettyVisibility())
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(8.dp))
        WorldsTab(
            worlds = selectedSection.worlds,
            onWorldClick = onWorldClick,
            modifier = Modifier.weight(1f),
        )
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
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        UserAvatar(imageUrl = user.displayAvatarUrl(), size = 96.dp, showStatusDot = false)
        Spacer(Modifier.height(12.dp))
        Text(user.displayName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (user.pronouns.isNotBlank()) {
            Text(
                user.pronouns,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // A payload that omitted tags would otherwise fall through to a
            // "Visitor" badge on a user whose real trust rank is unknown.
            if (user.tags.isNotEmpty()) TrustRankBadge(tags = user.tags)
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        user.state.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
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
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(100.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
