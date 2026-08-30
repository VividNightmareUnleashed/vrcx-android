package com.vrcx.android.ui.screen.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vrcx.android.data.api.model.CurrentUser
import com.vrcx.android.data.api.model.displayAvatarUrl
import com.vrcx.android.ui.components.TrustRankBadge
import com.vrcx.android.ui.components.UserAvatar
import com.vrcx.android.ui.components.VrcxCard
import com.vrcx.android.ui.components.VrcxInputField
import com.vrcx.android.ui.components.VrcxTopBar
import com.vrcx.android.ui.navigation.VrcxRoutes
import com.vrcx.android.ui.theme.vrcxColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(viewModel: ProfileViewModel = hiltViewModel(), onNavigate: (String) -> Unit = {}) {
    val user by viewModel.currentUser.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var editingField by remember { mutableStateOf<ProfileField?>(null) }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            VrcxTopBar(title = "Profile")

            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            ) {
                user?.let { currentUser ->
                    ProfileUserContent(
                        user = currentUser,
                        onEdit = { editingField = it },
                        onClearHome = viewModel::clearHomeLocation,
                    )
                }

                Spacer(Modifier.height(16.dp))

                ProfileNavigation(onNavigate)
                Spacer(Modifier.height(16.dp))
                ProfileLogoutButton(viewModel::logout)
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    editingField?.let { field ->
        ProfileEditDialog(
            field = field,
            user = user,
            onSave = { status, text ->
                when (field) {
                    ProfileField.STATUS -> viewModel.saveStatus(status, text)
                    ProfileField.BIO -> viewModel.saveBio(text)
                    ProfileField.PRONOUNS -> viewModel.savePronouns(text)
                }
                editingField = null
            },
            onDismiss = { editingField = null },
        )
    }
}

@Composable
private fun ProfileUserContent(user: CurrentUser, onEdit: (ProfileField) -> Unit, onClearHome: () -> Unit) {
    VrcxCard {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            UserAvatar(imageUrl = user.displayAvatarUrl(), size = 96.dp, showStatusDot = false)
            Spacer(Modifier.height(8.dp))
            Text(user.displayName, style = MaterialTheme.typography.titleLarge)
            TrustRankBadge(tags = user.tags)
            Spacer(Modifier.height(12.dp))
            ProfileEditableRow(
                label = "Status",
                value =
                    if (user.statusDescription.isBlank()) {
                        user.status
                    } else {
                        "${user.status}: ${user.statusDescription}"
                    },
                onEdit = { onEdit(ProfileField.STATUS) },
            )
            Spacer(Modifier.height(12.dp))
            ProfileEditableRow(
                label = "Pronouns",
                value = user.pronouns.ifBlank { "Not set" },
                onEdit = { onEdit(ProfileField.PRONOUNS) },
            )
            Spacer(Modifier.height(12.dp))
            ProfileEditableRow(
                label = "Bio",
                value = user.bio.ifBlank { "No bio yet" },
                onEdit = { onEdit(ProfileField.BIO) },
            )
        }
    }
    if (user.homeLocation.isNotBlank()) {
        Spacer(Modifier.height(12.dp))
        HomeLocationCard(user.homeLocation, onClearHome)
    }
}

@Composable
private fun HomeLocationCard(homeLocation: String, onClear: () -> Unit) {
    VrcxCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Home Location",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                Text(homeLocation, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onClear) {
                Text("Clear")
            }
        }
    }
}

@Composable
private fun ProfileNavigation(onNavigate: (String) -> Unit) {
    PROFILE_DESTINATIONS.forEach { destination ->
        NavItem(destination.icon, destination.label) {
            onNavigate(destination.route)
        }
    }
}

@Composable
private fun ProfileLogoutButton(onLogout: () -> Unit) {
    OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
        Icon(
            Icons.AutoMirrored.Filled.Logout,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.size(8.dp))
        Text("Logout", color = MaterialTheme.colorScheme.error)
    }
}

private data class ProfileDestination(val icon: ImageVector, val label: String, val route: String)

private val PROFILE_DESTINATIONS =
    listOf(
        ProfileDestination(Icons.Default.Home, "Dashboard", VrcxRoutes.DASHBOARD),
        ProfileDestination(Icons.Default.History, "Activity History", VrcxRoutes.GAME_LOG),
        ProfileDestination(
            Icons.AutoMirrored.Filled.ViewList,
            "Friends Roster",
            VrcxRoutes.PLAYER_LIST,
        ),
        ProfileDestination(Icons.Default.Build, "Tools", VrcxRoutes.TOOLS),
        ProfileDestination(Icons.Default.Favorite, "Favorites", VrcxRoutes.FAVORITES),
        ProfileDestination(Icons.Default.Group, "Groups", VrcxRoutes.GROUPS),
        ProfileDestination(Icons.Default.Person, "My Avatars", VrcxRoutes.MY_AVATARS),
        ProfileDestination(
            Icons.Default.LocationOn,
            "Friends Locations",
            VrcxRoutes.FRIENDS_LOCATIONS,
        ),
        ProfileDestination(Icons.Default.Image, "Gallery", VrcxRoutes.GALLERY),
        ProfileDestination(Icons.Default.History, "Friend Log", VrcxRoutes.FRIEND_LOG),
        ProfileDestination(Icons.Default.Block, "Moderation", VrcxRoutes.MODERATION),
        ProfileDestination(Icons.Default.BarChart, "Charts", VrcxRoutes.CHARTS),
        ProfileDestination(Icons.Default.Settings, "Settings", VrcxRoutes.SETTINGS),
    )

/** The editable fields of the signed-in user's own profile. */
private enum class ProfileField(val title: String, val placeholder: String) {
    STATUS("Edit Status", "Set a short status message"),
    BIO("Edit Bio", "Tell people a bit about yourself"),
    PRONOUNS("Edit Pronouns", "Add your pronouns"),
}

private val STATUS_OPTIONS = listOf("join me", "active", "ask me", "busy")

/**
 * One dialog for every editable profile field. The text seeds from the current
 * user each time the dialog opens and re-seeds if the user object refreshes
 * underneath it. Only the status field adds the preset chip row; [onSave]
 * receives the chosen status alongside the typed text.
 */
@Composable
private fun ProfileEditDialog(
    field: ProfileField,
    user: CurrentUser?,
    onSave: (status: String, text: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var status by remember(user) { mutableStateOf(user?.status ?: "active") }
    var text by remember(user) {
        mutableStateOf(
            when (field) {
                ProfileField.STATUS -> user?.statusDescription.orEmpty()
                ProfileField.BIO -> user?.bio.orEmpty()
                ProfileField.PRONOUNS -> user?.pronouns.orEmpty()
            },
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(field.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (field == ProfileField.STATUS) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        STATUS_OPTIONS.forEach { option ->
                            TextButton(onClick = { status = option }) {
                                Text(
                                    option,
                                    color = if (status == option) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                    }
                    Text("Description", style = MaterialTheme.typography.labelLarge)
                }
                VrcxInputField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = field.placeholder,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = field == ProfileField.STATUS,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(status, text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NavItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    val vrcxColors = MaterialTheme.vrcxColors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(vrcxColors.panelBackground)
            .border(1.dp, vrcxColors.panelBorder, MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.size(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = vrcxColors.panelMuted)
    }
}

@Composable
private fun ProfileEditableRow(label: String, value: String, onEdit: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onEdit, modifier = Modifier.size(24.dp)) {
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = "Edit $label",
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
