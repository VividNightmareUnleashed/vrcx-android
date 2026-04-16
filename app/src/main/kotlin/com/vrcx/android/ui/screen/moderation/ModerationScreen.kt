package com.vrcx.android.ui.screen.moderation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.api.model.PlayerModeration
import com.vrcx.android.data.repository.ModerationRepository
import com.vrcx.android.ui.components.EmptyState
import com.vrcx.android.ui.components.VrcxDetailTopBar
import com.vrcx.android.ui.components.VrcxSearchBar
import com.vrcx.android.ui.theme.LocalWallpaperActive
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

internal val MODERATION_TYPES = listOf("block", "mute", "hideAvatar", "showAvatar", "interactOff", "interactOn")
internal val TAB_LABELS = listOf("Blocked", "Muted", "Hide Avatar", "Show Avatar", "Interact Off", "Interact On")

internal fun moderationTabIndex(selectedType: String): Int =
    MODERATION_TYPES.indexOf(selectedType).takeIf { it >= 0 } ?: 0

@HiltViewModel
class ModerationViewModel @Inject constructor(
    private val moderationRepository: ModerationRepository,
) : ViewModel() {
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedType = MutableStateFlow("block")
    val selectedType: StateFlow<String> = _selectedType.asStateFlow()

    val filteredModerations: StateFlow<List<PlayerModeration>> = combine(
        moderationRepository.moderations,
        _selectedType,
        _searchQuery,
    ) { mods, type, query ->
        mods
            .filter { it.type == type }
            .filter { query.isBlank() || it.targetDisplayName.contains(query, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val countsByType: StateFlow<Map<String, Int>> = moderationRepository.moderations
        .map { mods -> mods.groupingBy { it.type }.eachCount() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    init { viewModelScope.launch { moderationRepository.loadModerations() } }

    fun selectType(type: String) { _selectedType.value = type }
    fun updateSearch(query: String) { _searchQuery.value = query }
    fun remove(id: String) { viewModelScope.launch { moderationRepository.deleteModeration(id) } }
    fun refresh() { viewModelScope.launch { moderationRepository.loadModerations() } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModerationScreen(viewModel: ModerationViewModel = hiltViewModel(), onBack: () -> Unit = {}) {
    val moderations by viewModel.filteredModerations.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedType by viewModel.selectedType.collectAsState()
    val countsByType by viewModel.countsByType.collectAsState()
    val selectedTabIndex = moderationTabIndex(selectedType)
    var pendingRemoveId by remember { mutableStateOf<Pair<String, String>?>(null) } // id to displayName

    Column(Modifier.fillMaxSize()) {
        VrcxDetailTopBar(
            title = "Moderation",
            onBack = onBack,
            actions = {
                IconButton(onClick = { viewModel.refresh() }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                }
            },
        )

        val isWallpaperActive = LocalWallpaperActive.current
        ScrollableTabRow(
            selectedTabIndex = selectedTabIndex,
            containerColor = MaterialTheme.colorScheme.surfaceContainer
                .let { if (isWallpaperActive) it.copy(alpha = 0.88f) else it },
        ) {
            MODERATION_TYPES.forEachIndexed { index, type ->
                val count = countsByType[type] ?: 0
                Tab(
                    selected = selectedType == type,
                    onClick = { viewModel.selectType(type) },
                    text = {
                        Text(if (count > 0) "${TAB_LABELS[index]} ($count)" else TAB_LABELS[index])
                    },
                )
            }
        }

        VrcxSearchBar(
            query = searchQuery,
            onQueryChange = { viewModel.updateSearch(it) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )

        if (moderations.isEmpty()) {
            EmptyState(message = "No ${TAB_LABELS[selectedTabIndex].lowercase()} users", icon = Icons.Outlined.Block)
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(moderations, key = { it.id }) { mod ->
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(mod.targetDisplayName, style = MaterialTheme.typography.bodyLarge)
                            Text(mod.created.take(10), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { pendingRemoveId = mod.id to mod.targetDisplayName }) {
                            Text("Remove")
                        }
                    }
                }
            }
        }
    }

    // Confirmation dialog
    pendingRemoveId?.let { (id, name) ->
        AlertDialog(
            onDismissRequest = { pendingRemoveId = null },
            title = { Text("Remove Moderation") },
            text = { Text("Remove ${TAB_LABELS[selectedTabIndex].lowercase()} moderation for $name?") },
            confirmButton = {
                TextButton(onClick = { viewModel.remove(id); pendingRemoveId = null }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { pendingRemoveId = null }) { Text("Cancel") } },
        )
    }
}
