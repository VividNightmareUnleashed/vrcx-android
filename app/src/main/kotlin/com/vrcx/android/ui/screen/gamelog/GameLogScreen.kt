package com.vrcx.android.ui.screen.gamelog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun GameLogScreen(
    viewModel: GameLogViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
    onUserClick: (String) -> Unit = {},
) {
    val page by viewModel.page.collectAsStateWithLifecycle()
    val controls by viewModel.controls.collectAsStateWithLifecycle()

    GameLogContent(
        state = GameLogUiState(
            page = page,
            controls = controls,
        ),
        onBack = onBack,
        onSearchChange = viewModel::updateSearch,
        onVipToggle = viewModel::toggleVipOnly,
        onFilterToggle = viewModel::toggleFilter,
        onRangeSelect = viewModel::selectRange,
        onScopeSelect = viewModel::selectScope,
        onUserClick = onUserClick,
        onLoadMore = viewModel::loadMore,
    )
}
