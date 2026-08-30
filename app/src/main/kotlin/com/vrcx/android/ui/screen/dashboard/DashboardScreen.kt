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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vrcx.android.data.api.model.CurrentUser
import com.vrcx.android.data.api.model.displayAvatarUrl
import com.vrcx.android.data.model.FriendContext
import com.vrcx.android.data.model.FriendState
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.data.repository.FeedEntry
import com.vrcx.android.data.repository.FeedEntryType
import com.vrcx.android.data.repository.FeedRepository
import com.vrcx.android.data.repository.FriendRepository
import com.vrcx.android.di.DefaultDispatcher
import com.vrcx.android.ui.common.activityLabel
import com.vrcx.android.ui.common.derivationScope
import com.vrcx.android.ui.common.relativeTime
import com.vrcx.android.ui.common.whileUiSubscribed
import com.vrcx.android.ui.components.EmptyState
import com.vrcx.android.ui.components.UserAvatar
import com.vrcx.android.ui.components.VrcxCard
import com.vrcx.android.ui.components.VrcxDetailTopBar
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

data class DashboardActivityBreakdown(val moves: Int = 0, val statusChanges: Int = 0, val avatarChanges: Int = 0)

/**
 * Everything the dashboard renders, derived from one snapshot of the friend map
 * and one slice of the feed, so the counters, the favourites row and the
 * activity breakdown can never describe different moments.
 */
data class DashboardUiState(
    val currentUser: CurrentUser? = null,
    val friendCounts: Map<FriendState, Int> = emptyMap(),
    val favoriteOnlineFriends: List<FriendContext> = emptyList(),
    val recentEntries: List<FeedEntry> = emptyList(),
    val activityBreakdown: DashboardActivityBreakdown = DashboardActivityBreakdown(),
)

private const val RECENT_ACTIVITY_LIMIT = 6
private const val FAVORITE_FRIEND_LIMIT = 5

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DashboardViewModel @Inject constructor(
    authRepository: AuthRepository,
    friendRepository: FriendRepository,
    feedRepository: FeedRepository,
    @DefaultDispatcher defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val currentUser = authRepository.authState
        .map { (it as? AuthState.LoggedIn)?.user }
        .distinctUntilChanged()

    // Seeded so the counters and the favourites row render as soon as the friend
    // map is there, rather than waiting on the first feed emission.
    private val recentEntries = currentUser
        .map { it?.id.orEmpty() }
        .distinctUntilChanged()
        .flatMapLatest { userId ->
            if (userId.isBlank()) {
                flowOf(emptyList())
            } else {
                feedRepository.getUnifiedFeed(userId).map { it.take(RECENT_ACTIVITY_LIMIT) }
            }
        }
        .onStart { emit(emptyList()) }

    val state: StateFlow<DashboardUiState> = combine(
        currentUser,
        friendRepository.friends,
        friendRepository.favoriteFriendIds,
        recentEntries,
    ) { user, friends, favoriteIds, entries ->
        DashboardUiState(
            currentUser = user,
            friendCounts = friends.values.groupingBy { it.state }.eachCount(),
            favoriteOnlineFriends = friends.values
                .filter { it.id in favoriteIds && it.state == FriendState.ONLINE }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                .take(FAVORITE_FRIEND_LIMIT),
            recentEntries = entries,
            activityBreakdown = DashboardActivityBreakdown(
                moves = entries.count { it.type == FeedEntryType.GPS },
                statusChanges = entries.count { it.type == FeedEntryType.STATUS },
                avatarChanges = entries.count { it.type == FeedEntryType.AVATAR },
            ),
        )
    }
        .stateIn(derivationScope(defaultDispatcher), whileUiSubscribed, DashboardUiState())
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
    onUserClick: (String) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentUser = state.currentUser
    val friendCounts = state.friendCounts
    val recentEntries = state.recentEntries
    val favoriteOnlineFriends = state.favoriteOnlineFriends
    val activityBreakdown = state.activityBreakdown

    Column(modifier = Modifier.fillMaxSize()) {
        VrcxDetailTopBar(title = "Dashboard", onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                DashboardUserCard(currentUser)
            }
            item {
                FriendMetrics(friendCounts)
            }
            item {
                ActivityMetrics(activityBreakdown)
            }
            favoriteFriendsSection(favoriteOnlineFriends, onUserClick)
            recentActivitySection(recentEntries, onUserClick)
        }
    }
}

@Composable
private fun DashboardUserCard(currentUser: CurrentUser?) {
    VrcxCard(Modifier.padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UserAvatar(
                imageUrl = currentUser?.displayAvatarUrl(),
                size = 56.dp,
                showStatusDot = false,
            )
            Spacer(Modifier.size(12.dp))
            Column {
                Text(
                    text = currentUser?.displayName ?: "Not signed in",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text =
                        currentUser?.statusDescription?.ifBlank { currentUser.status }
                            ?: "No status",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FriendMetrics(friendCounts: Map<FriendState, Int>) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FriendState.entries.forEach { state ->
            DashboardMetric(state.label, friendCounts[state] ?: 0, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ActivityMetrics(activity: DashboardActivityBreakdown) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DashboardMetric("Moves", activity.moves, Modifier.weight(1f))
        DashboardMetric("Status", activity.statusChanges, Modifier.weight(1f))
        DashboardMetric("Avatars", activity.avatarChanges, Modifier.weight(1f))
    }
}

private fun LazyListScope.favoriteFriendsSection(friends: List<FriendContext>, onUserClick: (String) -> Unit) {
    item {
        Text(
            text = "Favorite Friends Online",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
    if (friends.isEmpty()) {
        item {
            VrcxCard(Modifier.padding(horizontal = 16.dp)) {
                Text(
                    text = "No favorite friends are online right now.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    } else {
        items(friends, key = { "vip_${it.id}" }) { friend ->
            FavoriteFriendCard(friend, onUserClick)
        }
    }
}

@Composable
private fun FavoriteFriendCard(friend: FriendContext, onUserClick: (String) -> Unit) {
    VrcxCard(
        modifier =
            Modifier.padding(horizontal = 16.dp).clickable { onUserClick(friend.id) },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UserAvatar(
                imageUrl = friend.ref?.displayAvatarUrl(),
                status = friend.ref?.status,
                state = friend.state,
                size = 40.dp,
            )
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = friend.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text =
                        friend.ref
                            ?.statusDescription
                            ?.ifBlank { friend.state.label }
                            .orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = friend.state.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private fun LazyListScope.recentActivitySection(entries: List<FeedEntry>, onUserClick: (String) -> Unit) {
    item {
        Text(
            text = "Recent Activity",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
    if (entries.isEmpty()) {
        item {
            EmptyState(
                message = "No recent activity yet",
                subtitle = "Feed entries will show up here once your friends start moving around.",
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    } else {
        items(entries, key = { it.key }) { entry ->
            RecentActivityCard(entry, onUserClick)
        }
    }
}

@Composable
private fun RecentActivityCard(entry: FeedEntry, onUserClick: (String) -> Unit) {
    VrcxCard(
        modifier =
            Modifier.padding(horizontal = 16.dp).clickable { onUserClick(entry.userId) },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = relativeTime(entry.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = entry.activityLabel(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
