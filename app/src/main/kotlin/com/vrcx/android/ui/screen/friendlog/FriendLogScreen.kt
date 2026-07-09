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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.db.dao.FriendLogDao
import com.vrcx.android.data.db.entity.FriendLogHistoryEntity
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
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
import javax.inject.Inject

private val ALL_TYPES = setOf("Friend", "Unfriend", "DisplayName", "TrustLevel")

@HiltViewModel
class FriendLogViewModel @Inject constructor(
    authRepository: AuthRepository,
    friendLogDao: FriendLogDao,
) : ViewModel() {
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedTypes = MutableStateFlow(ALL_TYPES)
    val selectedTypes: StateFlow<Set<String>> = _selectedTypes.asStateFlow()

    private val rawHistory: StateFlow<List<FriendLogHistoryEntity>> = authRepository.authState
        .map { (it as? AuthState.LoggedIn)?.user?.id ?: "" }
        .flatMapLatest { uid ->
            if (uid.isEmpty()) flowOf(emptyList())
            else friendLogDao.getHistory(uid, limit = 200)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val history: StateFlow<List<FriendLogHistoryEntity>> = combine(
        rawHistory,
        _searchQuery,
        _selectedTypes,
    ) { entries, query, types ->
        entries
            .filter { it.type in types }
            .filter { query.isBlank() || it.displayName.contains(query, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun updateSearch(query: String) { _searchQuery.value = query }
    fun toggleType(type: String) {
        val current = _selectedTypes.value
        _selectedTypes.value = if (type in current) current - type else current + type
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
            ALL_TYPES.forEach { type ->
                FilterChip(
                    selected = type in selectedTypes,
                    onClick = { viewModel.toggleType(type) },
                    label = { Text(type) },
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
                            when (entry.type) {
                                "Friend" -> Icons.Default.PersonAdd
                                "Unfriend" -> Icons.Default.PersonRemove
                                "DisplayName" -> Icons.Outlined.Badge
                                "TrustLevel" -> Icons.Outlined.Shield
                                else -> Icons.Outlined.History
                            },
                            contentDescription = null,
                            tint = when (entry.type) {
                                "Friend" -> MaterialTheme.colorScheme.primary
                                "Unfriend" -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Spacer(Modifier.padding(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(entry.displayName, style = MaterialTheme.typography.bodyLarge)
                            val detail = when (entry.type) {
                                "DisplayName" -> if (entry.previousDisplayName.isNotEmpty()) "${entry.previousDisplayName} → ${entry.displayName}" else ""
                                "TrustLevel" -> if (entry.previousTrustLevel.isNotEmpty()) "${entry.previousTrustLevel} → ${entry.trustLevel}" else entry.trustLevel
                                else -> ""
                            }
                            Text(
                                "${entry.type}${if (detail.isNotEmpty()) " • $detail" else ""}",
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
