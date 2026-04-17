package com.vrcx.android.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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

/**
 * Bridges the legacy three-flag (isLoading, error, isEmpty) ViewModel pattern
 * to the unified Loading/Error/Empty/Success rendering. Use this when the
 * ViewModel hasn't been migrated to expose StateFlow<UiState<T>> directly —
 * screens still get consistent placeholder behavior without a VM restructure.
 *
 * The dispatch order is fixed: Loading wins over Error, Error wins over Empty,
 * Empty wins over Success. That matches the historical behavior of the
 * if/else ladders this helper replaces.
 */
@Composable
fun UiStateContainer(
    isLoading: Boolean,
    error: String?,
    isEmpty: Boolean,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    emptyMessage: String = "Nothing to show yet",
    emptySubtitle: String? = null,
    emptyIcon: ImageVector = Icons.Outlined.Inbox,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier) {
        when {
            isLoading -> LoadingState()
            error != null -> ErrorState(message = error, onRetry = onRetry)
            isEmpty -> EmptyState(
                message = emptyMessage,
                icon = emptyIcon,
                subtitle = emptySubtitle,
            )
            else -> content()
        }
    }
}
