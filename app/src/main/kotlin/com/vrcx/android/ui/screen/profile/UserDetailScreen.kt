package com.vrcx.android.ui.screen.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vrcx.android.data.api.model.VrcUser
import com.vrcx.android.data.api.model.displayAvatarUrl
import com.vrcx.android.ui.components.ConfirmDialog
import com.vrcx.android.ui.components.ErrorState
import com.vrcx.android.ui.components.LoadingState
import com.vrcx.android.ui.components.TrustRankBadge
import com.vrcx.android.ui.components.UserAvatar
import com.vrcx.android.ui.components.VrcxDetailTopBar
import com.vrcx.android.ui.components.VrcxTabRow
import com.vrcx.android.ui.theme.LocalWallpaperActive

private enum class UserDetailEditor {
    NOTE,
    MEMO,
}

private sealed interface UserDetailDestination {
    data class User(val id: String) : UserDetailDestination

    data class World(val id: String) : UserDetailDestination

    data class Group(val id: String) : UserDetailDestination

    data class Avatar(val id: String) : UserDetailDestination
}

private fun UserDetailViewModel.handleUiAction(
    action: UserDetailUiAction,
    onEdit: (UserDetailEditor) -> Unit,
    onRequestDestructiveConfirmation: (UserDestructiveAction) -> Unit,
) {
    when (action) {
        UserDetailUiAction.Reload -> onIntent(UserDetailIntent.Reload)

        UserDetailUiAction.ToggleFavorite -> onIntent(
            UserDetailIntent.Mutate(UserDetailMutation.ToggleFavorite),
        )

        is UserDetailUiAction.SelectTab -> onIntent(UserDetailIntent.SelectTab(action.tab))

        is UserDetailUiAction.SelectFavoriteWorldGroup -> onIntent(
            UserDetailIntent.SelectFavoriteWorldGroup(action.tag),
        )

        UserDetailUiAction.ToggleNotify -> onIntent(UserDetailIntent.Mutate(UserDetailMutation.ToggleNotify))

        UserDetailUiAction.SendBoop -> onIntent(
            UserDetailIntent.Mutate(UserDetailMutation.Social(UserDetailSocialAction.SEND_BOOP)),
        )

        UserDetailUiAction.SendFriendRequest -> onIntent(
            UserDetailIntent.Mutate(UserDetailMutation.Social(UserDetailSocialAction.SEND_FRIEND_REQUEST)),
        )

        UserDetailUiAction.CancelFriendRequest -> onIntent(
            UserDetailIntent.Mutate(UserDetailMutation.Social(UserDetailSocialAction.CANCEL_FRIEND_REQUEST)),
        )

        UserDetailUiAction.SendInvite -> onIntent(
            UserDetailIntent.Mutate(UserDetailMutation.Social(UserDetailSocialAction.SEND_INVITE)),
        )

        UserDetailUiAction.RequestInvite -> onIntent(
            UserDetailIntent.Mutate(UserDetailMutation.Social(UserDetailSocialAction.REQUEST_INVITE)),
        )

        UserDetailUiAction.EditNote -> onEdit(UserDetailEditor.NOTE)

        UserDetailUiAction.EditMemo -> onEdit(UserDetailEditor.MEMO)

        is UserDetailUiAction.RequestDestructiveConfirmation -> onRequestDestructiveConfirmation(action.action)
    }
}

private fun UserDetailViewModel.performDestructiveAction(action: UserDestructiveAction) {
    onIntent(UserDetailIntent.Mutate(UserDetailMutation.Social(action.socialAction)))
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
            viewModel.onIntent(UserDetailIntent.ClearMessage)
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
                val mutation = if (editor == UserDetailEditor.NOTE) {
                    UserDetailMutation.SaveNote(text)
                } else {
                    UserDetailMutation.SaveMemo(text)
                }
                viewModel.onIntent(UserDetailIntent.Mutate(mutation))
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
    onAction: (UserDetailUiAction) -> Unit,
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
                        IconButton(onClick = { onAction(UserDetailUiAction.ToggleFavorite) }) {
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
                onRetry = { onAction(UserDetailUiAction.Reload) },
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
    onAction: (UserDetailUiAction) -> Unit,
    onNavigate: (UserDetailDestination) -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        ProfileHeader(user)
        Spacer(Modifier.height(8.dp))

        VrcxTabRow(selectedTabIndex = state.selectedTab.ordinal) {
            UserDetailTab.entries.forEach { tab ->
                Tab(
                    selected = state.selectedTab == tab,
                    onClick = { onAction(UserDetailUiAction.SelectTab(tab)) },
                    text = { Text(tab.label) },
                )
            }
        }

        UserDetailTabContent(
            state = state,
            user = user,
            onAction = onAction,
            onUserClick = { onNavigate(UserDetailDestination.User(it)) },
            onWorldClick = { onNavigate(UserDetailDestination.World(it)) },
            onGroupClick = { onNavigate(UserDetailDestination.Group(it)) },
            onAvatarClick = { onNavigate(UserDetailDestination.Avatar(it)) },
        )
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
            // Missing tags mean the trust rank is unknown, rather than Visitor.
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
