package com.vrcx.android.ui.screen.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.model.FriendState
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.data.repository.FeedEntry
import com.vrcx.android.data.repository.FeedRepository
import com.vrcx.android.data.repository.FriendRepository
import com.vrcx.android.ui.common.relativeTime
import com.vrcx.android.ui.components.EmptyState
import com.vrcx.android.ui.components.UserAvatar
import com.vrcx.android.ui.components.VrcxCard
import com.vrcx.android.ui.components.VrcxDetailTopBar
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DashboardViewModel @Inject constructor(
    authRepository: AuthRepository,
    friendRepository: FriendRepository,
    feedRepository: FeedRepository,
) : ViewModel() {
    private val recentActivitySourceLimit = 6

    val currentUser = authRepository.authState
        .map { (it as? AuthState.LoggedIn)?.user }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val friendCounts: StateFlow<Triple<Int, Int, Int>> = friendRepository.friends
        .map { friends ->
            Triple(
                friends.values.count { it.state == FriendState.ONLINE },
                friends.values.count { it.state == FriendState.ACTIVE },
                friends.values.count { it.state == FriendState.OFFLINE },
            )
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Triple(0, 0, 0))

    val recentEntries: StateFlow<List<FeedEntry>> = authRepository.authState
        .map { (it as? AuthState.LoggedIn)?.user?.id.orEmpty() }
        .distinctUntilChanged()
        .flatMapLatest { userId ->
            if (userId.isBlank()) return@flatMapLatest flowOf(emptyList())
            combine(
                feedRepository.getGpsFeed(userId, recentActivitySourceLimit),
                feedRepository.getStatusFeed(userId, recentActivitySourceLimit),
                feedRepository.getBioFeed(userId, recentActivitySourceLimit),
                feedRepository.getAvatarFeed(userId, recentActivitySourceLimit),
                feedRepository.getOnlineOfflineFeed(userId, recentActivitySourceLimit),
            ) { gps, status, bio, avatar, onlineOffline ->
                buildList {
                    gps.forEach { add(FeedEntry(it.id, "gps", it.userId, it.displayName, it.worldName, it.previousLocation, it.createdAt)) }
                    status.forEach {
                        add(
                            FeedEntry(
                                it.id,
                                "status",
                                it.userId,
                                it.displayName,
                                "${it.status}: ${it.statusDescription}",
                                "${it.previousStatus}: ${it.previousStatusDescription}",
                                it.createdAt,
                            )
                        )
                    }
                    bio.forEach { add(FeedEntry(it.id, "bio", it.userId, it.displayName, it.bio, it.previousBio, it.createdAt)) }
                    avatar.forEach {
                        add(
                            FeedEntry(
                                it.id,
                                "avatar",
                                it.userId,
                                it.displayName,
                                it.avatarName.ifEmpty { "Avatar changed" },
                                "",
                                it.createdAt,
                                it.currentAvatarThumbnailImageUrl,
                            )
                        )
                    }
                    onlineOffline.forEach { add(FeedEntry(it.id, it.type, it.userId, it.displayName, it.worldName, "", it.createdAt)) }
                }.sortedByDescending { it.createdAt }.take(recentActivitySourceLimit)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
    onUserClick: (String) -> Unit = {},
) {
    val currentUser by viewModel.currentUser.collectAsState()
    val friendCounts by viewModel.friendCounts.collectAsState()
    val recentEntries by viewModel.recentEntries.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        VrcxDetailTopBar(title = "Dashboard", onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
        item {
            VrcxCard(Modifier.padding(horizontal = 16.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    UserAvatar(
                        imageUrl = currentUser?.currentAvatarThumbnailImageUrl,
                        size = 56.dp,
                        showStatusDot = false,
                    )
                    Spacer(Modifier.size(12.dp))
                    Column {
                        Text(currentUser?.displayName ?: "Not signed in", style = MaterialTheme.typography.titleMedium)
                        Text(
                            currentUser?.statusDescription?.ifBlank { currentUser?.status ?: "No status" } ?: "No status",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DashboardMetric("Online", friendCounts.first, Modifier.weight(1f))
                DashboardMetric("Active", friendCounts.second, Modifier.weight(1f))
                DashboardMetric("Offline", friendCounts.third, Modifier.weight(1f))
            }
        }
        item {
            Text(
                "Recent Activity",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (recentEntries.isEmpty()) {
            item {
                EmptyState(
                    message = "No recent activity yet",
                    subtitle = "Feed entries will show up here once your friends start moving around.",
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        } else {
            items(recentEntries, key = { "${it.type}_${it.id}" }) { entry ->
                VrcxCard(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .clickable { onUserClick(entry.userId) },
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(entry.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            Text(
                                relativeTime(entry.createdAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            when (entry.type) {
                                "gps" -> "Moved to ${entry.details}"
                                "status" -> entry.details
                                "online" -> "Came online"
                                "offline" -> "Went offline"
                                "avatar" -> entry.details
                                else -> entry.details
                            },
                            style = MaterialTheme.typography.bodyMedium,
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
private fun DashboardMetric(label: String, value: Int, modifier: Modifier = Modifier) {
    VrcxCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value.toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
    }
}
