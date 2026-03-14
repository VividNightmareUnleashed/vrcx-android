package com.vrcx.android.ui.screen.groups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vrcx.android.ui.components.VrcxCard
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.vrcx.android.data.api.model.Group
import com.vrcx.android.data.api.model.GroupInstance
import com.vrcx.android.data.api.model.GroupMember
import com.vrcx.android.data.repository.GroupRepository
import com.vrcx.android.ui.components.VrcxDetailTopBar
import com.vrcx.android.ui.components.LoadingState
import com.vrcx.android.ui.components.ErrorState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GroupDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val groupRepository: GroupRepository,
) : ViewModel() {
    private val groupId: String = savedStateHandle.get<String>("groupId") ?: ""

    private val _group = MutableStateFlow<Group?>(null)
    val group: StateFlow<Group?> = _group.asStateFlow()

    private val _members = MutableStateFlow<List<GroupMember>>(emptyList())
    val members: StateFlow<List<GroupMember>> = _members.asStateFlow()

    private val _instances = MutableStateFlow<List<GroupInstance>>(emptyList())
    val instances: StateFlow<List<GroupInstance>> = _instances.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init { loadGroup() }

    fun loadGroup() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                _group.value = groupRepository.getGroup(groupId)
                _members.value = groupRepository.getGroupMembers(groupId)
                _instances.value = groupRepository.getGroupInstances(groupId)
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load group"
            } finally {
                _isLoading.value = false
            }
        }
    }
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun GroupDetailScreen(viewModel: GroupDetailViewModel = hiltViewModel(), onUserClick: (String) -> Unit = {}, onBack: () -> Unit = {}) {
    val group by viewModel.group.collectAsState()
    val members by viewModel.members.collectAsState()
    val instances by viewModel.instances.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        VrcxDetailTopBar(title = group?.name ?: "Group", onBack = onBack)

        if (isLoading) {
            LoadingState()
            return
        }

        if (error != null && group == null) {
            ErrorState(error ?: "Failed to load group", onRetry = { viewModel.loadGroup() })
            return
        }

        val g = group ?: run {
            ErrorState("Group not found")
            return
        }
        // Banner
        if (g.bannerUrl.isNotEmpty()) {
            AsyncImage(model = g.bannerUrl, contentDescription = null, modifier = Modifier.fillMaxWidth().aspectRatio(3f).clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)), contentScale = ContentScale.Crop)
        }

        // Info
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (g.iconUrl.isNotEmpty()) {
                    AsyncImage(model = g.iconUrl, contentDescription = null, modifier = Modifier.size(48.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                    Spacer(Modifier.width(12.dp))
                }
                Column {
                    Text(g.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("${g.memberCount} members • ${g.shortCode}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (g.description.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(g.description, style = MaterialTheme.typography.bodyMedium, maxLines = 4)
            }
        }

        // Tabs
        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Members (${members.size})") })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Instances (${instances.size})") })
        }

        when (selectedTab) {
            0 -> LazyColumn(Modifier.fillMaxSize()) {
                items(members, key = { it.id }) { member ->
                    Row(Modifier.fillMaxWidth().clickable { onUserClick(member.userId) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(model = member.user?.currentAvatarThumbnailImageUrl, contentDescription = null, modifier = Modifier.size(40.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(member.user?.displayName ?: member.userId, style = MaterialTheme.typography.bodyLarge)
                            Text("Joined ${member.joinedAt.take(10)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            1 -> LazyColumn(Modifier.fillMaxSize()) {
                items(instances, key = { it.instanceId }) { instance ->
                    VrcxCard(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(instance.world?.name ?: instance.location, style = MaterialTheme.typography.bodyLarge)
                            Text("${instance.memberCount} members", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
