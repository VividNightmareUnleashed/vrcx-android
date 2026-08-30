package com.vrcx.android.ui.screen.notifications

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vrcx.android.data.repository.InviteMessageTemplate
import com.vrcx.android.data.repository.NotificationCategoryFilter
import com.vrcx.android.data.repository.UnifiedNotification
import com.vrcx.android.ui.common.LoadState

@Composable
fun NotificationsScreen(viewModel: NotificationsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val actions = remember(viewModel) { NotificationsActionDispatcher(viewModel) }

    NotificationsErrorEffect(state, snackbarHostState, actions)
    Box(modifier = Modifier.fillMaxSize()) {
        NotificationsContent(state = state, actions = actions)
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
    state.action.inviteResponseDialog?.let { dialog ->
        InviteResponseDialog(dialog = dialog, actions = actions)
    }
}

@Composable
private fun NotificationsErrorEffect(
    state: NotificationsUiState,
    snackbarHostState: SnackbarHostState,
    actions: NotificationsActionDispatcher,
) {
    val transientError = state.transientError()
    LaunchedEffect(transientError) {
        transientError?.let { error ->
            snackbarHostState.showSnackbar(error.message)
            actions(error.consumedAction)
        }
    }
}

private fun NotificationsUiState.transientError(): NotificationsTransientError? {
    val actionError = action.error
    if (actionError != null) {
        return NotificationsTransientError(
            message = actionError,
            consumedAction = NotificationsUiAction.ConsumeActionError(action.errorId),
        )
    }
    return (loadState as? LoadState.Loaded)?.staleError?.let { message ->
        NotificationsTransientError(
            message = message,
            consumedAction = NotificationsUiAction.ConsumeLoadError(loadErrorId),
        )
    }
}

private data class NotificationsTransientError(val message: String, val consumedAction: NotificationsUiAction)

internal sealed interface NotificationsUiAction {
    sealed interface ListAction : NotificationsUiAction

    sealed interface InboxAction : NotificationsUiAction

    sealed interface DialogAction : NotificationsUiAction

    sealed interface ErrorAction : NotificationsUiAction

    data class SelectCategory(val category: NotificationCategoryFilter) : ListAction

    data class ToggleType(val type: String) : ListAction

    data object Refresh : ListAction

    data class PerformPrimary(val notification: UnifiedNotification) : InboxAction

    data class Respond(val notification: UnifiedNotification, val responseType: String) : InboxAction

    data class OpenInviteResponseDialog(val notification: UnifiedNotification) : InboxAction

    data class Hide(val notification: UnifiedNotification) : InboxAction

    data class DeclineFriendRequest(val notification: UnifiedNotification) : InboxAction

    data object DismissInviteResponseDialog : DialogAction

    data object RefreshInviteResponseDialog : DialogAction

    data class SendInviteResponse(val requestId: Long, val template: InviteMessageTemplate) : DialogAction

    data class ConsumeActionError(val errorId: Long) : ErrorAction

    data class ConsumeLoadError(val errorId: Long) : ErrorAction
}

internal class NotificationsActionDispatcher(private val viewModel: NotificationsViewModel) {
    operator fun invoke(action: NotificationsUiAction) {
        when (action) {
            is NotificationsUiAction.ListAction -> dispatchListAction(action)
            is NotificationsUiAction.InboxAction -> dispatchInboxAction(action)
            is NotificationsUiAction.DialogAction -> dispatchDialogAction(action)
            is NotificationsUiAction.ErrorAction -> dispatchErrorAction(action)
        }
    }

    private fun dispatchListAction(action: NotificationsUiAction.ListAction) {
        when (action) {
            is NotificationsUiAction.SelectCategory -> viewModel.selectCategory(action.category)
            is NotificationsUiAction.ToggleType -> viewModel.toggleTypeFilter(action.type)
            NotificationsUiAction.Refresh -> viewModel.refresh()
        }
    }

    private fun dispatchInboxAction(action: NotificationsUiAction.InboxAction) {
        when (action) {
            is NotificationsUiAction.PerformPrimary -> viewModel.performPrimaryAction(action.notification)

            is NotificationsUiAction.Respond -> viewModel.respond(action.notification, action.responseType)

            is NotificationsUiAction.OpenInviteResponseDialog ->
                viewModel.openInviteResponseDialog(action.notification)

            is NotificationsUiAction.Hide -> viewModel.hide(action.notification)

            is NotificationsUiAction.DeclineFriendRequest -> viewModel.declineFriendRequest(action.notification)
        }
    }

    private fun dispatchDialogAction(action: NotificationsUiAction.DialogAction) {
        when (action) {
            NotificationsUiAction.DismissInviteResponseDialog -> viewModel.dismissInviteResponseDialog()

            NotificationsUiAction.RefreshInviteResponseDialog -> viewModel.refreshInviteResponseDialog()

            is NotificationsUiAction.SendInviteResponse ->
                viewModel.sendInviteResponse(action.requestId, action.template)
        }
    }

    private fun dispatchErrorAction(action: NotificationsUiAction.ErrorAction) {
        when (action) {
            is NotificationsUiAction.ConsumeActionError -> viewModel.consumeActionError(action.errorId)
            is NotificationsUiAction.ConsumeLoadError -> viewModel.consumeLoadError(action.errorId)
        }
    }
}
