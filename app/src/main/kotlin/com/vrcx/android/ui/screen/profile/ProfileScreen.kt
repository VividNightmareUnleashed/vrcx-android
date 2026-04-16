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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.vrcx.android.data.api.UserApi
import com.vrcx.android.data.api.model.CurrentUser
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.service.WebSocketForegroundService
import com.vrcx.android.ui.components.TrustRankBadge
import com.vrcx.android.ui.components.UserAvatar
import com.vrcx.android.ui.components.VrcxCard
import com.vrcx.android.ui.components.VrcxTopBar
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val currentUser: StateFlow<CurrentUser?> = authRepository.authState.map { state ->
        (state as? AuthState.LoggedIn)?.user
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun saveStatus(status: String, statusDescription: String) {
        viewModelScope.launch {
            try {
                val uid = currentUser.value?.id ?: return@launch
                userApi.saveCurrentUser(uid, mapOf("status" to status, "statusDescription" to statusDescription))
                authRepository.fetchCurrentUser()
                _message.value = "Status updated"
            } catch (e: Exception) { _message.value = "Failed: ${e.message}" }
        }
    }

    fun saveBio(bio: String) {
        viewModelScope.launch {
            try {
                val uid = currentUser.value?.id ?: return@launch
                userApi.saveCurrentUser(uid, mapOf("bio" to bio))
                authRepository.fetchCurrentUser()
                _message.value = "Bio updated"
            } catch (e: Exception) { _message.value = "Failed: ${e.message}" }
        }
    }

    fun clearMessage() { _message.value = null }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
            WebSocketForegroundService.stop(context)
        }
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
                            UserAvatar(imageUrl = u.currentAvatarThumbnailImageUrl, size = 96.dp, showStatusDot = false)
                            Spacer(Modifier.height(8.dp))
                            Text(u.displayName, style = MaterialTheme.typography.titleLarge)
                            TrustRankBadge(tags = u.tags)
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${u.status}: ${u.statusDescription}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                IconButton(onClick = { showStatusDialog = true }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Outlined.Edit, contentDescription = "Edit status", modifier = Modifier.size(16.dp))
                                }
                            }
                            if (u.bio.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(u.bio, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                    IconButton(onClick = { showBioDialog = true }, modifier = Modifier.size(24.dp)) {
                                        Icon(Icons.Outlined.Edit, contentDescription = "Edit bio", modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                NavItem(Icons.Default.Home, "Dashboard") { onNavigate("dashboard") }
                NavItem(Icons.Default.History, "Game Log") { onNavigate("game_log") }
                NavItem(Icons.Default.ViewList, "Player List") { onNavigate("player_list") }
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
                    OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth())
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
            text = { OutlinedTextField(value = bio, onValueChange = { bio = it }, modifier = Modifier.fillMaxWidth(), maxLines = 8) },
            confirmButton = { TextButton(onClick = { viewModel.saveBio(bio); showBioDialog = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { showBioDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NavItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.size(12.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
