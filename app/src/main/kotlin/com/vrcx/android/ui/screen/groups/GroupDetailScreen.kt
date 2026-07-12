package com.vrcx.android.ui.screen.groups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import com.vrcx.android.data.api.model.displayAvatarUrl
import com.vrcx.android.data.repository.GroupRepository
import com.vrcx.android.ui.common.UiStateContainer
import com.vrcx.android.ui.common.relativeTime
import com.vrcx.android.ui.components.ConfirmDialog
import com.vrcx.android.ui.components.ErrorState
import com.vrcx.android.ui.components.LoadingState
import com.vrcx.android.ui.components.VrcxCard
import com.vrcx.android.ui.components.VrcxDetailTopBar
import com.vrcx.android.ui.theme.LocalWallpaperActive
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.supervisorScope
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

    private val _membersLoading = MutableStateFlow(true)
    val membersLoading: StateFlow<Boolean> = _membersLoading.asStateFlow()
    private val _instancesLoading = MutableStateFlow(true)
    val instancesLoading: StateFlow<Boolean> = _instancesLoading.asStateFlow()
    private val _postsLoading = MutableStateFlow(true)
    val postsLoading: StateFlow<Boolean> = _postsLoading.asStateFlow()

    private val _membersError = MutableStateFlow<String?>(null)
    val membersError: StateFlow<String?> = _membersError.asStateFlow()
    private val _instancesError = MutableStateFlow<String?>(null)
    val instancesError: StateFlow<String?> = _instancesError.asStateFlow()
    private val _postsError = MutableStateFlow<String?>(null)
    val postsError: StateFlow<String?> = _postsError.asStateFlow()

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
                // Run the four loads in parallel — they're independent server-side and
                // sequencing them was 4x perceived latency for nothing.
                supervisorScope {
                    val groupAsync = async { loadResult { groupRepository.getGroup(groupId) } }
                    val membersAsync = async { loadResult { groupRepository.getGroupMembers(groupId) } }
                    val instancesAsync = async { loadResult { groupRepository.getGroupInstances(groupId) } }
                    val postsAsync = async { loadResult { groupRepository.getGroupPosts(groupId) } }
                    groupAsync.await().fold(
                        onSuccess = { _group.value = it },
                        onFailure = { _error.value = it.message ?: "Failed to load group" },
                    )
                    applyMembersResult(membersAsync.await())
                    applyInstancesResult(instancesAsync.await())
                    applyPostsResult(postsAsync.await())
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load group"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun retryMembers() = viewModelScope.launch {
        _membersLoading.value = true
        _membersError.value = null
        applyMembersResult(loadResult { groupRepository.getGroupMembers(groupId) })
    }

    fun retryInstances() = viewModelScope.launch {
        _instancesLoading.value = true
        _instancesError.value = null
        applyInstancesResult(loadResult { groupRepository.getGroupInstances(groupId) })
    }

    fun retryPosts() = viewModelScope.launch {
        _postsLoading.value = true
        _postsError.value = null
        applyPostsResult(loadResult { groupRepository.getGroupPosts(groupId) })
    }

    private fun applyMembersResult(result: Result<List<GroupMember>>) {
        result.onSuccess { _members.value = it }.onFailure { _membersError.value = it.message ?: "Failed to load members" }
        _membersLoading.value = false
    }

    private fun applyInstancesResult(result: Result<List<GroupInstance>>) {
        result.onSuccess { _instances.value = it }.onFailure { _instancesError.value = it.message ?: "Failed to load instances" }
        _instancesLoading.value = false
    }

    private fun applyPostsResult(result: Result<List<GroupPost>>) {
        result.onSuccess { _posts.value = it }.onFailure { _postsError.value = it.message ?: "Failed to load posts" }
        _postsLoading.value = false
    }

    private suspend fun <T> loadResult(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * Gate kick/remove UI on the caller actually holding the
     * `group-members-manage` permission (or the `"*"` wildcard owners get).
     *
     * A prior version used `roleIds.isNotEmpty()` as a cheap heuristic, but
     * every group membership includes at least a default member role, so that
     * check surfaced destructive controls to regular members — who then hit
     * `kickGroupMember` → 403 → error snackbar on every attempt. The server
     * still enforces permissions authoritatively; this just keeps the UI
     * honest. Matches desktop VRCX's `hasGroupPermission(..., 'group-members-manage')`.
     */
    fun canManageMembers(group: Group?): Boolean {
        val myMember = group?.myMember ?: return false
        if (!isMember(group)) return false
        return myMember.permissions.contains("*") ||
            myMember.permissions.contains("group-members-manage")
    }

    fun kickMember(userId: String) {
        viewModelScope.launch {
            _isActionLoading.value = true
            try {
                val ok = groupRepository.kickGroupMember(groupId, userId)
                if (ok) {
                    _members.value = _members.value.filterNot { it.userId == userId }
                    _message.value = "Member removed"
                } else {
                    _message.value = "Failed to remove member (insufficient permissions?)"
                }
            } catch (e: Exception) {
                _message.value = e.message ?: "Failed to remove member"
            } finally {
                _isActionLoading.value = false
            }
        }
    }

    fun canRemoveMember(group: Group?, member: GroupMember): Boolean {
        if (!canManageMembers(group)) return false
        return member.userId.isNotBlank() &&
            member.userId != group?.ownerId &&
            member.userId != group?.myMember?.userId
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
    val group by viewModel.group.collectAsStateWithLifecycle()
    val members by viewModel.members.collectAsStateWithLifecycle()
    val instances by viewModel.instances.collectAsStateWithLifecycle()
    val posts by viewModel.posts.collectAsStateWithLifecycle()
    val membersLoading by viewModel.membersLoading.collectAsStateWithLifecycle()
    val instancesLoading by viewModel.instancesLoading.collectAsStateWithLifecycle()
    val postsLoading by viewModel.postsLoading.collectAsStateWithLifecycle()
    val membersError by viewModel.membersError.collectAsStateWithLifecycle()
    val instancesError by viewModel.instancesError.collectAsStateWithLifecycle()
    val postsError by viewModel.postsError.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isActionLoading by viewModel.isActionLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableIntStateOf(0) }
    var pendingKickUserId by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        VrcxDetailTopBar(title = group?.name ?: "Group", onBack = onBack)
    Column(Modifier.fillMaxWidth().weight(1f)) {

        if (isLoading) {
            LoadingState()
            return@Column
        }

        if (error != null && group == null) {
            ErrorState(error ?: "Failed to load group", onRetry = { viewModel.loadGroup() })
            return@Column
        }

        val currentGroup = group ?: run {
            ErrorState("Group not found")
            return@Column
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
            0 -> UiStateContainer(
                isLoading = membersLoading,
                error = membersError,
                isEmpty = members.isEmpty(),
                onRetry = viewModel::retryMembers,
                emptyMessage = "No group members",
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(Modifier.fillMaxSize()) {
                val canManage = viewModel.canManageMembers(currentGroup)
                items(members, key = { it.id }) { member ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onUserClick(member.userId) }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = member.user?.displayAvatarUrl(),
                            contentDescription = null,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(member.user?.displayName ?: member.userId, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Joined ${member.joinedAt.take(10)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (canManage && viewModel.canRemoveMember(currentGroup, member)) {
                            var menuOpen by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(Icons.Outlined.MoreVert, contentDescription = "Member actions")
                                }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Remove from group") },
                                        leadingIcon = { Icon(Icons.Outlined.PersonRemove, contentDescription = null) },
                                        onClick = {
                                            menuOpen = false
                                            pendingKickUserId = member.userId
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            }
            1 -> UiStateContainer(
                isLoading = instancesLoading,
                error = instancesError,
                isEmpty = instances.isEmpty(),
                onRetry = viewModel::retryInstances,
                emptyMessage = "No active group instances",
                modifier = Modifier.fillMaxSize(),
            ) {
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
            else -> UiStateContainer(
                isLoading = postsLoading,
                error = postsError,
                isEmpty = posts.isEmpty(),
                onRetry = viewModel::retryPosts,
                emptyMessage = "No group posts yet",
                modifier = Modifier.fillMaxSize(),
            ) {
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
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }

    pendingKickUserId?.let { userId ->
        val displayName = members.firstOrNull { it.userId == userId }?.user?.displayName ?: userId
        ConfirmDialog(
            title = "Remove $displayName?",
            message = "They'll be removed from this group. They can rejoin if the group allows it.",
            confirmLabel = "Remove",
            onConfirm = {
                viewModel.kickMember(userId)
                pendingKickUserId = null
            },
            onDismiss = { pendingKickUserId = null },
        )
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
