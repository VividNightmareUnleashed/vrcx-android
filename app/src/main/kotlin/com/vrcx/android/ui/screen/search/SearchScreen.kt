@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.vrcx.android.ui.screen.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vrcx.android.ui.components.VrcxSearchBar
import com.vrcx.android.ui.components.VrcxTabRow
import com.vrcx.android.ui.components.VrcxTopBar

@Composable
fun SearchScreen(
    viewModel: SearchViewModel = hiltViewModel(),
    onUserClick: (String) -> Unit = {},
    onWorldClick: (String) -> Unit = {},
    onAvatarClick: (String) -> Unit = {},
    onGroupClick: (String) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Column(modifier = Modifier.fillMaxSize()) {
        VrcxTopBar(title = "Search")
        VrcxSearchBar(
            query = state.query,
            onQueryChange = viewModel::updateQuery,
            placeholder = searchPlaceholder(state),
        )
        VrcxTabRow(selectedTabIndex = state.selectedTab.ordinal) {
            SearchTab.entries.forEach { tab ->
                Tab(
                    selected = state.selectedTab == tab,
                    onClick = { viewModel.selectTab(tab) },
                    text = { Text(tab.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }
        SearchFilters(state, viewModel)
        SearchResults(
            state = state,
            viewModel = viewModel,
            navigation =
                SearchNavigation(
                    onUserClick,
                    onWorldClick,
                    onAvatarClick,
                    onGroupClick,
                ),
        )
    }
}
