package com.vrcx.android.ui.screen.favorites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.api.model.Favorite
import com.vrcx.android.data.repository.FavoriteRepository
import com.vrcx.android.ui.components.EmptyState
import com.vrcx.android.ui.components.VrcxCard
import com.vrcx.android.ui.components.VrcxDetailTopBar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val favoriteRepository: FavoriteRepository,
) : ViewModel() {
    val favorites: StateFlow<List<Favorite>> = favoriteRepository.favorites
    init {
        viewModelScope.launch {
            favoriteRepository.loadFavorites()
            favoriteRepository.loadFavoriteGroups()
        }
    }
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun FavoritesScreen(viewModel: FavoritesViewModel = hiltViewModel(), onBack: () -> Unit = {}) {
    val favorites by viewModel.favorites.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Friends", "Worlds", "Avatars")

    Column(modifier = Modifier.fillMaxSize()) {
        VrcxDetailTopBar(title = "Favorites", onBack = onBack)
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(title) })
            }
        }
        val type = listOf("friend", "world", "avatar")[selectedTab]
        val filtered = favorites.filter { it.type == type }
        if (filtered.isEmpty()) {
            EmptyState(message = "No ${tabs[selectedTab].lowercase()} favorites", icon = Icons.Outlined.FavoriteBorder)
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filtered, key = { it.id }) { fav ->
                    VrcxCard {
                        Column(Modifier.padding(16.dp)) {
                            Text(fav.favoriteId, style = MaterialTheme.typography.bodyMedium)
                            Text("Group: ${fav.tags.joinToString()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
