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

/**
 * Dispatches a ViewModel's (isLoading, error, isEmpty) flags to the shared
 * Loading/Error/Empty placeholders, so screens get consistent placeholder
 * behavior without hand-rolling the ladder.
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
