package com.vrcx.android.ui.screen.moderation

import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vrcx.android.data.api.model.PlayerModeration
import com.vrcx.android.ui.common.LoadState
import com.vrcx.android.ui.common.isBusy
import com.vrcx.android.ui.components.ConfirmDialog
import com.vrcx.android.ui.components.EmptyState
import com.vrcx.android.ui.components.ErrorState
import com.vrcx.android.ui.components.LoadingState
import com.vrcx.android.ui.components.VrcxDetailTopBar
import com.vrcx.android.ui.components.VrcxScrollableTabRow
import com.vrcx.android.ui.components.VrcxSearchBar

private const val ISO_DATE_LENGTH = 10

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModerationScreen(viewModel: ModerationViewModel = hiltViewModel(), onBack: () -> Unit = {}) {
    val moderations by viewModel.moderations.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val screenState by viewModel.screenState.collectAsStateWithLifecycle()
    var pendingRemove by remember { mutableStateOf<PlayerModeration?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(screenState.staleError) {
        screenState.staleError?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.consumeError()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ModerationTopBar(screenState.load.isBusy, onBack, viewModel::refresh)
            ModerationTabs(selectedTab, moderations.countsByType, viewModel::selectTab)
            VrcxSearchBar(
                query = searchQuery,
                onQueryChange = viewModel::updateSearch,
                modifier =
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            ModerationContent(
                state = screenState,
                selectedTab = selectedTab,
                moderations = moderations.visible,
                onRetry = viewModel::refresh,
                onRemove = { pendingRemove = it },
            )
        }
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }

    pendingRemove?.let { moderation ->
        ConfirmDialog(
            title = "Remove Moderation",
            message =
                "Remove ${selectedTab.label.lowercase()} moderation for " +
                    "${moderation.targetDisplayName}?",
            confirmLabel = "Remove",
            onConfirm = {
                viewModel.remove(moderation)
                pendingRemove = null
            },
            onDismiss = { pendingRemove = null },
        )
    }
}

@Composable
private fun ModerationTopBar(isLoading: Boolean, onBack: () -> Unit, onRefresh: () -> Unit) {
    VrcxDetailTopBar(
        title = "Moderation",
        onBack = onBack,
        actions = {
            IconButton(onClick = onRefresh, enabled = !isLoading) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
            }
        },
    )
}

@Composable
private fun ModerationTabs(
    selectedTab: ModerationTab,
    countsByType: Map<String, Int>,
    onSelectTab: (ModerationTab) -> Unit,
) {
    VrcxScrollableTabRow(selectedTabIndex = MODERATION_TABS.indexOf(selectedTab)) {
        MODERATION_TABS.forEach { tab ->
            val count = countsByType[tab.type] ?: 0
            Tab(
                selected = selectedTab == tab,
                onClick = { onSelectTab(tab) },
                text = { Text(if (count > 0) "${tab.label} ($count)" else tab.label) },
            )
        }
    }
}

@Composable
private fun ModerationContent(
    state: ModerationViewModel.ScreenState,
    selectedTab: ModerationTab,
    moderations: List<PlayerModeration>,
    onRetry: () -> Unit,
    onRemove: (PlayerModeration) -> Unit,
) {
    when (val load = state.load) {
        LoadState.NotLoaded, LoadState.Loading -> LoadingState()

        is LoadState.Failed -> ErrorState(message = load.message, onRetry = onRetry)

        is LoadState.Loaded ->
            if (moderations.isEmpty()) {
                EmptyState(
                    message = "No ${selectedTab.label.lowercase()} users",
                    icon = Icons.Outlined.Block,
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(moderations, key = { it.id }) { moderation ->
                        ModerationRow(moderation, !state.isMutating, onRemove)
                    }
                }
            }
    }
}

@Composable
private fun ModerationRow(
    moderation: PlayerModeration,
    actionsEnabled: Boolean,
    onRemove: (PlayerModeration) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(moderation.targetDisplayName, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = moderation.created.take(ISO_DATE_LENGTH),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = { onRemove(moderation) }, enabled = actionsEnabled) {
            Text("Remove")
        }
    }
}
