package com.vrcx.android.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.vrcx.android.ui.components.EmptyState
import com.vrcx.android.ui.components.ErrorState
import com.vrcx.android.ui.components.LoadingState

@Composable
fun <T> UiStateHandler(
    state: UiState<T>,
    modifier: Modifier = Modifier,
    onLoading: @Composable () -> Unit = { LoadingState() },
    onError: @Composable (String, (() -> Unit)?) -> Unit = { msg, retry -> ErrorState(msg, retry) },
    onEmpty: @Composable (String, (() -> Unit)?) -> Unit = { msg, action ->
        EmptyState(message = msg, onAction = action, actionLabel = if (action != null) "Retry" else null)
    },
    onSuccess: @Composable (T) -> Unit,
) {
    Box(modifier = modifier) {
        when (state) {
            is UiState.Loading -> onLoading()
            is UiState.Error -> onError(state.message, state.retry)
            is UiState.Empty -> onEmpty(state.message, state.action)
            is UiState.Success -> onSuccess(state.data)
        }
    }
}
