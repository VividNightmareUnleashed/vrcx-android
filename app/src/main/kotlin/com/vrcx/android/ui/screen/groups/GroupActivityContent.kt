package com.vrcx.android.ui.screen.groups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.vrcx.android.data.api.model.GroupInstance
import com.vrcx.android.data.api.model.GroupPost
import com.vrcx.android.ui.common.LoadState
import com.vrcx.android.ui.common.relativeTime
import com.vrcx.android.ui.components.EmptyState
import com.vrcx.android.ui.components.ErrorState
import com.vrcx.android.ui.components.LoadingState
import com.vrcx.android.ui.components.VrcxCard

@Composable
internal fun InstancesTab(state: LoadState<List<GroupInstance>>, onRetry: () -> Unit) {
    when (state) {
        LoadState.Loading,
        LoadState.NotLoaded,
        -> LoadingState()

        is LoadState.Failed -> ErrorState(state.message, onRetry = onRetry)

        is LoadState.Loaded -> if (state.value.isEmpty()) {
            EmptyState("No active group instances")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.value, key = { it.instanceId }) { instance ->
                    GroupInstanceCard(instance)
                }
            }
        }
    }
}

@Composable
private fun GroupInstanceCard(instance: GroupInstance) {
    VrcxCard(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                instance.world?.name ?: instance.location,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "${instance.memberCount} members",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun PostsTab(state: LoadState<GroupPagedData<GroupPost>>, onRetry: () -> Unit, onLoadMore: () -> Unit) {
    when (state) {
        LoadState.Loading,
        LoadState.NotLoaded,
        -> LoadingState()

        is LoadState.Failed -> ErrorState(state.message, onRetry = onRetry)

        is LoadState.Loaded -> if (state.value.items.isEmpty()) {
            EmptyState("No group posts yet")
        } else {
            PostsList(state.value, onLoadMore)
        }
    }
}

@Composable
private fun PostsList(page: GroupPagedData<GroupPost>, onLoadMore: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(page.items, key = GroupPost::id) { post ->
            GroupPostCard(post)
        }
        if (page.hasMore || page.appendState !is GroupAppendState.Idle) {
            item(key = "posts-pagination") {
                PaginationFooter(page.appendState, page.nextOffset, onLoadMore)
            }
        }
    }
}

@Composable
private fun GroupPostCard(post: GroupPost) {
    VrcxCard(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (post.title.isNotBlank()) {
                Text(post.title, style = MaterialTheme.typography.titleMedium)
            }
            if (post.text.isNotBlank()) {
                Text(post.text, style = MaterialTheme.typography.bodyMedium)
            }
            if (post.imageUrl.isNotBlank()) {
                AsyncImage(
                    model = post.imageUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(POST_IMAGE_ASPECT_RATIO)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop,
                )
            }
            Text(
                relativeTime(post.updatedAt.ifBlank { post.createdAt }),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val POST_IMAGE_ASPECT_RATIO = 16f / 9f
