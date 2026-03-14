package com.vrcx.android.ui.screen.moderation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.api.model.PlayerModeration
import com.vrcx.android.data.repository.ModerationRepository
import com.vrcx.android.ui.components.EmptyState
import com.vrcx.android.ui.components.VrcxDetailTopBar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ModerationViewModel @Inject constructor(
    private val moderationRepository: ModerationRepository,
) : ViewModel() {
    val moderations: StateFlow<List<PlayerModeration>> = moderationRepository.moderations
    init { viewModelScope.launch { moderationRepository.loadModerations() } }
    fun remove(id: String) { viewModelScope.launch { moderationRepository.deleteModeration(id) } }
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun ModerationScreen(viewModel: ModerationViewModel = hiltViewModel(), onBack: () -> Unit = {}) {
    val moderations by viewModel.moderations.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        VrcxDetailTopBar(title = "Moderation", onBack = onBack)
        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Blocked") })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Muted") })
        }
        val type = if (selectedTab == 0) "block" else "mute"
        val filtered = moderations.filter { it.type == type }
        if (filtered.isEmpty()) {
            EmptyState(
                message = "No ${if (selectedTab == 0) "blocked" else "muted"} users",
                icon = if (selectedTab == 0) Icons.Outlined.Block else Icons.Outlined.VolumeOff,
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(filtered, key = { it.id }) { mod ->
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(mod.targetDisplayName, style = MaterialTheme.typography.bodyLarge)
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { viewModel.remove(mod.id) }) {
                            Text(if (selectedTab == 0) "Unblock" else "Unmute")
                        }
                    }
                }
            }
        }
    }
}
