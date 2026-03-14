package com.vrcx.android.ui.screen.groups

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Group
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.vrcx.android.data.api.model.Group
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.data.repository.GroupRepository
import com.vrcx.android.ui.components.EmptyState
import com.vrcx.android.ui.components.VrcxDetailTopBar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GroupsViewModel @Inject constructor(
    private val groupRepository: GroupRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {
    val groups: StateFlow<List<Group>> = groupRepository.userGroups
    init {
        viewModelScope.launch {
            val userId = (authRepository.authState.value as? AuthState.LoggedIn)?.user?.id ?: return@launch
            groupRepository.loadUserGroups(userId)
        }
    }
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun GroupsScreen(viewModel: GroupsViewModel = hiltViewModel(), onGroupClick: (String) -> Unit = {}, onBack: () -> Unit = {}) {
    val groups by viewModel.groups.collectAsState()
    Column(Modifier.fillMaxSize()) {
        VrcxDetailTopBar(title = "Groups", onBack = onBack)
        if (groups.isEmpty()) {
            EmptyState(message = "No groups", icon = Icons.Outlined.Group, subtitle = "Join groups in VRChat to see them here")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
            items(groups, key = { it.id }) { group ->
                Row(Modifier.fillMaxWidth().clickable { onGroupClick(group.groupId.ifEmpty { group.id }) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(model = group.iconUrl, contentDescription = null, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(group.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${group.memberCount} members", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        }
    }
}
