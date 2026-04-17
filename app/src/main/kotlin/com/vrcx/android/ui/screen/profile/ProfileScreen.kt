package com.vrcx.android.ui.screen.profile

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.api.UserApi
import com.vrcx.android.data.api.model.CurrentUser
import com.vrcx.android.data.api.model.displayAvatarUrl
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.ui.components.TrustRankBadge
import com.vrcx.android.ui.components.UserAvatar
import com.vrcx.android.ui.components.VrcxCard
import com.vrcx.android.ui.components.VrcxInputField
import com.vrcx.android.ui.components.VrcxTopBar
import com.vrcx.android.ui.theme.vrcxColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val userApi: UserApi,
) : ViewModel() {
    val currentUser: StateFlow<CurrentUser?> = authRepository.authState.map { state ->
        (state as? AuthState.LoggedIn)?.user
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private fun updateCurrentUser(
        payload: Map<String, Any>,
        successMessage: String,
    ) {
        viewModelScope.launch {
            try {
                val uid = currentUser.value?.id ?: return@launch
                userApi.saveCurrentUser(uid, payload)
                authRepository.fetchCurrentUser()
                _message.value = successMessage
            } catch (e: Exception) {
                _message.value = "Failed: ${e.message}"
            }
        }
    }

    fun saveStatus(status: String, statusDescription: String) {
        updateCurrentUser(
            payload = mapOf(
                "status" to status,
                "statusDescription" to statusDescription,
            ),
            successMessage = "Status updated",
        )
    }

    fun saveBio(bio: String) {
        updateCurrentUser(
            payload = mapOf("bio" to bio),
            successMessage = "Bio updated",
        )
    }

    fun savePronouns(pronouns: String) {
        updateCurrentUser(
            payload = mapOf("pronouns" to pronouns),
            successMessage = "Pronouns updated",
        )
    }

    fun clearHomeLocation() {
        updateCurrentUser(
            payload = mapOf("homeLocation" to ""),
            successMessage = "Home location cleared",
        )
    }

    fun clearMessage() { _message.value = null }

    fun logout() {
        // authRepository.logout() now tears down the websocket service itself,
        // so ProfileViewModel no longer has to stop it separately.
        viewModelScope.launch { authRepository.logout() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel = hiltViewModel(),
    onNavigate: (String) -> Unit = {},
) {
    val user by viewModel.currentUser.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showStatusDialog by remember { mutableStateOf(false) }
    var showBioDialog by remember { mutableStateOf(false) }
    var showPronounsDialog by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let { snackbarHostState.showSnackbar(it); viewModel.clearMessage() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            VrcxTopBar(title = "Profile")

            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            ) {
                user?.let { u ->
                    VrcxCard {
                        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            UserAvatar(imageUrl = u.displayAvatarUrl(), size = 96.dp, showStatusDot = false)
                            Spacer(Modifier.height(8.dp))
                            Text(u.displayName, style = MaterialTheme.typography.titleLarge)
                            TrustRankBadge(tags = u.tags)
                            Spacer(Modifier.height(12.dp))
                            ProfileEditableRow(
                                label = "Status",
                                value = if (u.statusDescription.isBlank()) u.status else "${u.status}: ${u.statusDescription}",
                                onEdit = { showStatusDialog = true },
                            )
                            Spacer(Modifier.height(12.dp))
                            ProfileEditableRow(
                                label = "Pronouns",
                                value = u.pronouns.ifBlank { "Not set" },
                                onEdit = { showPronounsDialog = true },
                            )
                            Spacer(Modifier.height(12.dp))
                            ProfileEditableRow(
                                label = "Bio",
                                value = u.bio.ifBlank { "No bio yet" },
                                onEdit = { showBioDialog = true },
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    if (u.homeLocation.isNotBlank()) {
                        VrcxCard {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Home Location", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.height(4.dp))
                                    Text(u.homeLocation, style = MaterialTheme.typography.bodySmall)
                                }
                                TextButton(onClick = { viewModel.clearHomeLocation() }) {
                                    Text("Clear")
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                NavItem(Icons.Default.Home, "Dashboard") { onNavigate("dashboard") }
                NavItem(Icons.Default.History, "Activity History") { onNavigate("game_log") }
                NavItem(Icons.Default.ViewList, "Friends Roster") { onNavigate("player_list") }
                NavItem(Icons.Default.Build, "Tools") { onNavigate("tools") }
                NavItem(Icons.Default.Favorite, "Favorites") { onNavigate("favorites") }
                NavItem(Icons.Default.Group, "Groups") { onNavigate("groups") }
                NavItem(Icons.Default.Person, "My Avatars") { onNavigate("my_avatars") }
                NavItem(Icons.Default.LocationOn, "Friends Locations") { onNavigate("friends_locations") }
                NavItem(Icons.Default.Image, "Gallery") { onNavigate("gallery") }
                NavItem(Icons.Default.History, "Friend Log") { onNavigate("friend_log") }
                NavItem(Icons.Default.Block, "Moderation") { onNavigate("moderation") }
                NavItem(Icons.Default.BarChart, "Charts") { onNavigate("charts") }
                NavItem(Icons.Default.Settings, "Settings") { onNavigate("settings") }

                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = { viewModel.logout() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.size(8.dp))
                    Text("Logout", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    // Status edit dialog
    if (showStatusDialog) {
        var status by remember { mutableStateOf(user?.status ?: "active") }
        var description by remember { mutableStateOf(user?.statusDescription ?: "") }
        AlertDialog(
            onDismissRequest = { showStatusDialog = false },
            title = { Text("Edit Status") },
            text = {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("join me", "active", "ask me", "busy").forEach { s ->
                            TextButton(onClick = { status = s }) {
                                Text(s, color = if (status == s) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Description", style = MaterialTheme.typography.labelLarge)
                        VrcxInputField(
                            value = description,
                            onValueChange = { description = it },
                            placeholder = "Set a short status message",
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.saveStatus(status, description); showStatusDialog = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { showStatusDialog = false }) { Text("Cancel") } },
        )
    }

    // Bio edit dialog
    if (showBioDialog) {
        var bio by remember { mutableStateOf(user?.bio ?: "") }
        AlertDialog(
            onDismissRequest = { showBioDialog = false },
            title = { Text("Edit Bio") },
            text = {
                VrcxInputField(
                    value = bio,
                    onValueChange = { bio = it },
                    placeholder = "Tell people a bit about yourself",
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                )
            },
            confirmButton = { TextButton(onClick = { viewModel.saveBio(bio); showBioDialog = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { showBioDialog = false }) { Text("Cancel") } },
        )
    }

    if (showPronounsDialog) {
        var pronouns by remember { mutableStateOf(user?.pronouns ?: "") }
        AlertDialog(
            onDismissRequest = { showPronounsDialog = false },
            title = { Text("Edit Pronouns") },
            text = {
                VrcxInputField(
                    value = pronouns,
                    onValueChange = { pronouns = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "Add your pronouns",
                    singleLine = false,
                )
            },
            confirmButton = { TextButton(onClick = { viewModel.savePronouns(pronouns); showPronounsDialog = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { showPronounsDialog = false }) { Text("Cancel") } },
        )
    }
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
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = vrcxColors.panelMuted)
    }
}

@Composable
private fun ProfileEditableRow(
    label: String,
    value: String,
    onEdit: () -> Unit,
) {
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
