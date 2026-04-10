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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.vrcx.android.data.api.model.GroupPost
import com.vrcx.android.data.repository.GroupRepository
import com.vrcx.android.ui.common.relativeTime
import com.vrcx.android.ui.components.EmptyState
import com.vrcx.android.ui.components.ErrorState
import com.vrcx.android.ui.components.LoadingState
import com.vrcx.android.ui.components.VrcxCard
import com.vrcx.android.ui.components.VrcxDetailTopBar
import com.vrcx.android.ui.theme.LocalWallpaperActive
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    private val _posts = MutableStateFlow<List<GroupPost>>(emptyList())
    val posts: StateFlow<List<GroupPost>> = _posts.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isActionLoading = MutableStateFlow(false)
    val isActionLoading: StateFlow<Boolean> = _isActionLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init { loadGroup() }

    fun loadGroup() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                _group.value = groupRepository.getGroup(groupId)
                _members.value = groupRepository.getGroupMembers(groupId)
                _instances.value = groupRepository.getGroupInstances(groupId)
                _posts.value = groupRepository.getGroupPosts(groupId)
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load group"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun joinOrLeaveGroup() {
        viewModelScope.launch {
            val currentGroup = _group.value ?: return@launch
            _isActionLoading.value = true
            try {
                _group.value = if (isMember(currentGroup)) {
                    groupRepository.leaveGroup(currentGroup.id)
                } else {
                    groupRepository.joinGroup(currentGroup.id)
                }
                _message.value = if (isMember(currentGroup)) {
                    "Left group"
                } else {
                    if (membershipStatus(_group.value) == "requested") "Join request sent" else "Joined group"
                }
            } catch (e: Exception) {
                _message.value = e.message ?: "Group action failed"
            } finally {
                _isActionLoading.value = false
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    fun membershipStatus(group: Group?): String {
        return group?.myMember?.membershipStatus
            ?.takeIf { it.isNotBlank() }
            ?: group?.membershipStatus.orEmpty()
    }

    fun isMember(group: Group?): Boolean = membershipStatus(group) == "member"
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun GroupDetailScreen(
    viewModel: GroupDetailViewModel = hiltViewModel(),
    onUserClick: (String) -> Unit = {},
    onBack: () -> Unit = {},
) {
    val group by viewModel.group.collectAsState()
    val members by viewModel.members.collectAsState()
    val instances by viewModel.instances.collectAsState()
    val posts by viewModel.posts.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isActionLoading by viewModel.isActionLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val message by viewModel.message.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Column(Modifier.fillMaxSize()) {
        VrcxDetailTopBar(title = group?.name ?: "Group", onBack = onBack)
        SnackbarHost(hostState = snackbarHostState)

        if (isLoading) {
            LoadingState()
            return
        }

        if (error != null && group == null) {
            ErrorState(error ?: "Failed to load group", onRetry = { viewModel.loadGroup() })
            return
        }

        val currentGroup = group ?: run {
            ErrorState("Group not found")
            return
        }

        if (currentGroup.bannerUrl.isNotEmpty()) {
            AsyncImage(
                model = currentGroup.bannerUrl,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f)
                    .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)),
                contentScale = ContentScale.Crop,
            )
        }

        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (currentGroup.iconUrl.isNotEmpty()) {
                    AsyncImage(
                        model = currentGroup.iconUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(currentGroup.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "${currentGroup.memberCount} members • ${currentGroup.shortCode}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isActionLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    GroupActionButton(
                        membershipStatus = viewModel.membershipStatus(currentGroup),
                        onClick = viewModel::joinOrLeaveGroup,
                    )
                }
            }
            if (currentGroup.description.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(currentGroup.description, style = MaterialTheme.typography.bodyMedium, maxLines = 4)
            }
        }

        val isWallpaperActive = LocalWallpaperActive.current
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.surfaceContainer
                .let { if (isWallpaperActive) it.copy(alpha = 0.88f) else it },
        ) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Members (${members.size})") })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Instances (${instances.size})") })
            Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("Posts (${posts.size})") })
        }

        when (selectedTab) {
            0 -> LazyColumn(Modifier.fillMaxSize()) {
                items(members, key = { it.id }) { member ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onUserClick(member.userId) }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = member.user?.currentAvatarThumbnailImageUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(member.user?.displayName ?: member.userId, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Joined ${member.joinedAt.take(10)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            1 -> {
                if (instances.isEmpty()) {
                    EmptyState("No active group instances")
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(instances, key = { it.instanceId }) { instance ->
                            VrcxCard(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(instance.world?.name ?: instance.location, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "${instance.memberCount} members",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            else -> {
                if (posts.isEmpty()) {
                    EmptyState("No group posts yet")
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(posts, key = { it.id }) { post ->
                            VrcxCard(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (post.title.isNotBlank()) {
                                        Text(post.title, style = MaterialTheme.typography.titleMedium)
                                    }
                                    if (post.text.isNotBlank()) {
                                        Text(post.text, style = MaterialTheme.typography.bodyMedium)
                                    }
                                    if (post.imageUrl.isNotBlank()) {
                                        AsyncImage(
                                            model = post.imageUrl,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .aspectRatio(16f / 9f)
                                                .clip(RoundedCornerShape(12.dp)),
                                            contentScale = ContentScale.Crop,
                                        )
                                    }
                                    Text(
                                        relativeTime(post.updatedAt.ifBlank { post.createdAt }),
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
    }
}

@Composable
private fun GroupActionButton(membershipStatus: String, onClick: () -> Unit) {
    when (membershipStatus) {
        "member" -> OutlinedButton(onClick = onClick) { Text("Leave Group") }
        "requested" -> FilledTonalButton(onClick = {}, enabled = false) { Text("Request Pending") }
        else -> FilledTonalButton(onClick = onClick) {
            Text(if (membershipStatus == "invited") "Join Group" else "Request / Join")
        }
    }
}
