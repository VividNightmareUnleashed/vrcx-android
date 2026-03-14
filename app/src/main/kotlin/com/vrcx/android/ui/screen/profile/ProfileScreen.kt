package com.vrcx.android.ui.screen.profile

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
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val currentUser: StateFlow<CurrentUser?> = authRepository.authState.map { state ->
        (state as? AuthState.LoggedIn)?.user
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

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
    val user by viewModel.currentUser.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        VrcxTopBar(title = "Profile")

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            // Profile header
            user?.let { u ->
                VrcxCard {
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        UserAvatar(
                            imageUrl = u.currentAvatarThumbnailImageUrl,
                            size = 96.dp,
                            showStatusDot = false,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(u.displayName, style = MaterialTheme.typography.titleLarge)
                        TrustRankBadge(tags = u.tags)
                        Spacer(Modifier.height(4.dp))
                        Text("${u.status}: ${u.statusDescription}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (u.bio.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(u.bio, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Navigation items
            NavItem(Icons.Default.Favorite, "Favorites") { onNavigate("favorites") }
            NavItem(Icons.Default.Group, "Groups") { onNavigate("groups") }
            NavItem(Icons.Default.Person, "My Avatars") { onNavigate("my_avatars") }
            NavItem(Icons.Default.LocationOn, "Friends Locations") { onNavigate("friends_locations") }
            NavItem(Icons.Default.Image, "Gallery") { onNavigate("gallery") }
            NavItem(Icons.Default.History, "Friend Log") { onNavigate("friend_log") }
            NavItem(Icons.Default.Block, "Moderation") { onNavigate("moderation") }
            NavItem(Icons.Default.Settings, "Settings") { onNavigate("settings") }

            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = { viewModel.logout() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.size(8.dp))
                Text("Logout", color = MaterialTheme.colorScheme.error)
            }
        }
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
