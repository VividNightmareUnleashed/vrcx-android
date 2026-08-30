package com.vrcx.android.ui.screen.groups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.vrcx.android.data.repository.canonicalGroupId
import com.vrcx.android.ui.common.UiStateContainer
import com.vrcx.android.ui.components.VrcxDetailTopBar

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun GroupsScreen(
    viewModel: GroupsViewModel = hiltViewModel(),
    onGroupClick: (String) -> Unit = {},
    onBack: () -> Unit = {},
) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        VrcxDetailTopBar(title = "Groups", onBack = onBack)
        UiStateContainer(
            isLoading = isLoading,
            error = error,
            isEmpty = groups.isEmpty(),
            onRetry = viewModel::loadGroups,
            emptyMessage = "No groups",
            emptySubtitle = "Join groups in VRChat to see them here",
            emptyIcon = Icons.Outlined.Group,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
                items(groups, key = { it.id }) { group ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onGroupClick(group.canonicalGroupId()) }
                                .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = group.iconUrl,
                            contentDescription = null,
                            modifier =
                                Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = group.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${group.memberCount} members",
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
