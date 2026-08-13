package com.vrcx.android.ui.screen.friendlog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.vrcx.android.data.db.entity.FriendLogHistoryEntity
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.data.repository.FriendLogEventType
import com.vrcx.android.data.repository.FriendRepository
import com.vrcx.android.ui.common.derivationScope
import com.vrcx.android.ui.common.relativeTime
import com.vrcx.android.ui.components.EmptyState
import com.vrcx.android.ui.components.VrcxDetailTopBar
import com.vrcx.android.ui.components.VrcxSearchBar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import javax.inject.Inject

private const val HISTORY_LIMIT = 200

/** One friend-log row, with the persisted type token resolved to its kind. */
data class FriendLogEntry(
    val id: Long,
    val type: FriendLogEventType,
    val displayName: String,
    val previousDisplayName: String,
    val trustLevel: String,
    val previousTrustLevel: String,
    val createdAt: String,
)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class FriendLogViewModel @Inject constructor(
    authRepository: AuthRepository,
    friendRepository: FriendRepository,
) : ViewModel() {
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedTypes = MutableStateFlow(FriendLogEventType.entries.toSet())
    val selectedTypes: StateFlow<Set<FriendLogEventType>> = _selectedTypes.asStateFlow()

    private val rawHistory = authRepository.authState
        .map { (it as? AuthState.LoggedIn)?.user?.id ?: "" }
        .flatMapLatest { uid ->
            if (uid.isEmpty()) flowOf(emptyList()) else friendRepository.friendLogHistory(uid, HISTORY_LIMIT)
        }

    val history: StateFlow<List<FriendLogEntry>> = combine(
        rawHistory,
        _searchQuery,
        _selectedTypes,
    ) { entries, query, types ->
        entries
            .mapNotNull { it.toFriendLogEntry() }
            .filter { it.type in types }
            .filter { query.isBlank() || it.displayName.contains(query, ignoreCase = true) }
    }
        .stateIn(derivationScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun updateSearch(query: String) { _searchQuery.value = query }

    fun toggleType(type: FriendLogEventType) {
        val current = _selectedTypes.value
        _selectedTypes.value = if (type in current) current - type else current + type
    }

    // A row whose token this build does not know is dropped rather than rendered
    // without an icon or a label.
    private fun FriendLogHistoryEntity.toFriendLogEntry(): FriendLogEntry? {
        val eventType = FriendLogEventType.fromToken(type) ?: return null
        return FriendLogEntry(
            id = id,
            type = eventType,
            displayName = displayName,
            previousDisplayName = previousDisplayName,
            trustLevel = trustLevel,
            previousTrustLevel = previousTrustLevel,
            createdAt = createdAt,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FriendLogScreen(viewModel: FriendLogViewModel = hiltViewModel(), onBack: () -> Unit = {}) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedTypes by viewModel.selectedTypes.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        VrcxDetailTopBar(title = "Friend Log", onBack = onBack)

        VrcxSearchBar(
            query = searchQuery,
            onQueryChange = { viewModel.updateSearch(it) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )

        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FriendLogEventType.entries.forEach { type ->
                FilterChip(
                    selected = type in selectedTypes,
                    onClick = { viewModel.toggleType(type) },
                    label = { Text(type.label) },
                )
            }
        }

        if (history.isEmpty()) {
            EmptyState(message = "No friend log history", icon = Icons.Outlined.History, subtitle = "History will appear as friends are added/removed")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(history, key = { it.id }) { entry ->
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            entry.type.icon(),
                            contentDescription = null,
                            tint = when (entry.type) {
                                FriendLogEventType.FRIEND -> MaterialTheme.colorScheme.primary
                                FriendLogEventType.UNFRIEND -> MaterialTheme.colorScheme.error
                                FriendLogEventType.DISPLAY_NAME,
                                FriendLogEventType.TRUST_LEVEL,
                                -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Spacer(Modifier.padding(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(entry.displayName, style = MaterialTheme.typography.bodyLarge)
                            val detail = when (entry.type) {
                                FriendLogEventType.DISPLAY_NAME ->
                                    if (entry.previousDisplayName.isNotEmpty()) "${entry.previousDisplayName} → ${entry.displayName}" else ""
                                FriendLogEventType.TRUST_LEVEL ->
                                    if (entry.previousTrustLevel.isNotEmpty()) "${entry.previousTrustLevel} → ${entry.trustLevel}" else entry.trustLevel
                                FriendLogEventType.FRIEND, FriendLogEventType.UNFRIEND -> ""
                            }
                            Text(
                                "${entry.type.label}${if (detail.isNotEmpty()) " • $detail" else ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            relativeTime(entry.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun FriendLogEventType.icon(): ImageVector = when (this) {
    FriendLogEventType.FRIEND -> Icons.Default.PersonAdd
    FriendLogEventType.UNFRIEND -> Icons.Default.PersonRemove
    FriendLogEventType.DISPLAY_NAME -> Icons.Outlined.Badge
    FriendLogEventType.TRUST_LEVEL -> Icons.Outlined.Shield
}
