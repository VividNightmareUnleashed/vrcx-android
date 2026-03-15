package com.vrcx.android.ui.screen.avatars

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.vrcx.android.data.api.model.Avatar
import com.vrcx.android.data.repository.AvatarRepository
import com.vrcx.android.ui.components.EmptyState
import com.vrcx.android.ui.components.VrcxCard
import com.vrcx.android.ui.components.VrcxDetailTopBar
import com.vrcx.android.ui.components.VrcxSearchBar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AvatarsViewModel @Inject constructor(
    private val avatarRepository: AvatarRepository,
) : ViewModel() {
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedVisibility = MutableStateFlow<String?>(null)
    val selectedVisibility: StateFlow<String?> = _selectedVisibility.asStateFlow()

    private val _selectedPlatform = MutableStateFlow<String?>(null)
    val selectedPlatform: StateFlow<String?> = _selectedPlatform.asStateFlow()

    val filteredAvatars: StateFlow<List<Avatar>> = combine(
        avatarRepository.myAvatars,
        _searchQuery,
        _selectedVisibility,
        _selectedPlatform,
    ) { avatars, query, visibility, platform ->
        avatars
            .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
            .filter { visibility == null || it.releaseStatus == visibility }
            .filter { platform == null || it.unityPackages.any { pkg -> pkg.platform == platform } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init { viewModelScope.launch { avatarRepository.loadMyAvatars() } }

    fun updateSearch(query: String) { _searchQuery.value = query }
    fun toggleVisibility(v: String) { _selectedVisibility.value = if (_selectedVisibility.value == v) null else v }
    fun togglePlatform(p: String) { _selectedPlatform.value = if (_selectedPlatform.value == p) null else p }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MyAvatarsScreen(viewModel: AvatarsViewModel = hiltViewModel(), onBack: () -> Unit = {}, onAvatarClick: (String) -> Unit = {}) {
    val avatars by viewModel.filteredAvatars.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedVisibility by viewModel.selectedVisibility.collectAsState()
    val selectedPlatform by viewModel.selectedPlatform.collectAsState()

    Column(Modifier.fillMaxSize()) {
        VrcxDetailTopBar(title = "My Avatars", onBack = onBack)

        VrcxSearchBar(
            query = searchQuery,
            onQueryChange = { viewModel.updateSearch(it) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )

        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = selectedVisibility == "public", onClick = { viewModel.toggleVisibility("public") }, label = { Text("Public") })
            FilterChip(selected = selectedVisibility == "private", onClick = { viewModel.toggleVisibility("private") }, label = { Text("Private") })
            FilterChip(selected = selectedPlatform == "standalonewindows", onClick = { viewModel.togglePlatform("standalonewindows") }, label = { Text("PC") })
            FilterChip(selected = selectedPlatform == "android", onClick = { viewModel.togglePlatform("android") }, label = { Text("Quest") })
        }

        if (avatars.isEmpty()) {
            EmptyState(message = "No avatars", icon = Icons.Outlined.Face, subtitle = "Your owned avatars will appear here")
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                Modifier.fillMaxSize().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(avatars, key = { it.id }) { avatar ->
                    VrcxCard(onClick = { onAvatarClick(avatar.id) }) {
                        Column {
                            AsyncImage(
                                model = avatar.thumbnailImageUrl,
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                                contentScale = ContentScale.Crop,
                            )
                            Text(avatar.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
