package com.vrcx.android.ui.screen.avatars

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Face
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vrcx.android.ui.components.VrcxCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
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
import com.vrcx.android.ui.components.VrcxDetailTopBar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AvatarsViewModel @Inject constructor(
    private val avatarRepository: AvatarRepository,
) : ViewModel() {
    val avatars: StateFlow<List<Avatar>> = avatarRepository.myAvatars
    init { viewModelScope.launch { avatarRepository.loadMyAvatars() } }
    fun selectAvatar(avatarId: String) { viewModelScope.launch { avatarRepository.selectAvatar(avatarId) } }
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun MyAvatarsScreen(viewModel: AvatarsViewModel = hiltViewModel(), onBack: () -> Unit = {}) {
    val avatars by viewModel.avatars.collectAsState()
    Column(Modifier.fillMaxSize()) {
        VrcxDetailTopBar(title = "My Avatars", onBack = onBack)
        if (avatars.isEmpty()) {
            EmptyState(message = "No avatars", icon = Icons.Outlined.Face, subtitle = "Your owned avatars will appear here")
        } else {
        LazyVerticalGrid(columns = GridCells.Fixed(2), Modifier.fillMaxSize().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(avatars, key = { it.id }) { avatar ->
                VrcxCard(onClick = { viewModel.selectAvatar(avatar.id) }) {
                    Column {
                        AsyncImage(model = avatar.thumbnailImageUrl, contentDescription = null, modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)), contentScale = ContentScale.Crop)
                        Text(avatar.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        }
    }
}
