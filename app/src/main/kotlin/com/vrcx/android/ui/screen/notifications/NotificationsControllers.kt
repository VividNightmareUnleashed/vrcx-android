package com.vrcx.android.ui.screen.notifications

import com.vrcx.android.data.repository.InviteMessageRepository
import com.vrcx.android.data.repository.InviteMessageTemplate
import com.vrcx.android.data.repository.NotificationKind
import com.vrcx.android.data.repository.NotificationRepository
import com.vrcx.android.data.repository.UnifiedNotification
import com.vrcx.android.ui.common.LoadState
import com.vrcx.android.ui.common.completeLoad
import com.vrcx.android.ui.common.failLoad
import com.vrcx.android.ui.common.isBusy
import com.vrcx.android.ui.common.settleLoad
import com.vrcx.android.ui.common.startLoad
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class NotificationsLoadCoordinator(
    private val scope: CoroutineScope,
    private val notificationRepository: NotificationRepository,
) {
    private val mutableState = MutableStateFlow(NotificationLoad())
    val state: StateFlow<NotificationLoad> = mutableState.asStateFlow()

    fun start() {
        scope.launch {
            mutableState.update { it.copy(state = it.state.startLoad()) }
            try {
                val failure = recoverableResult {
                    notificationRepository.restoreNotifications()
                }.exceptionOrNull()
                if (failure == null) {
                    if (notificationRepository.unifiedNotifications.value.isNotEmpty()) {
                        mutableState.update { it.copy(state = it.state.completeLoad(Unit)) }
                    }
                } else {
                    publishFailure(failure, "Failed to restore notifications")
                }
            } finally {
                mutableState.update { it.copy(state = it.state.settleLoad()) }
            }
            refresh()
        }
    }

    fun refresh() {
        if (mutableState.value.state.isBusy) return
        mutableState.update { it.copy(state = it.state.startLoad()) }
        scope.launch {
            try {
                val failure = recoverableResult {
                    notificationRepository.loadNotifications()
                }.exceptionOrNull()
                if (failure == null) {
                    mutableState.update { it.copy(state = it.state.completeLoad(Unit)) }
                } else {
                    publishFailure(failure, "Failed to load notifications")
                }
            } finally {
                mutableState.update { it.copy(state = it.state.settleLoad()) }
            }
        }
    }

    fun consumeError(errorId: Long) {
        mutableState.update { current ->
            if (current.errorId != errorId) return@update current
            val consumedState = when {
                current.state is LoadState.Loaded -> current.state.copy(staleError = null)

                current.state is LoadState.Failed &&
                    notificationRepository.unifiedNotifications.value.isNotEmpty() -> LoadState.Loaded(Unit)

                else -> current.state
            }
            current.copy(state = consumedState)
        }
    }

    private fun publishFailure(failure: Throwable, fallbackMessage: String) {
        mutableState.update {
            it.copy(
                state = it.state.failLoad(failure.message ?: fallbackMessage),
                errorId = it.errorId + 1,
            )
        }
    }
}

internal class NotificationActionController(
    private val scope: CoroutineScope,
    private val notificationRepository: NotificationRepository,
    private val state: MutableStateFlow<NotificationsActionState>,
) {
    fun performPrimaryAction(notification: UnifiedNotification) {
        launchAction("Failed to handle notification") {
            notificationRepository.performPrimaryAction(notification)
        }
    }

    fun respond(notification: UnifiedNotification, responseType: String) {
        launchAction("Failed to respond to notification") {
            notificationRepository.respondToNotification(notification, responseType)
        }
    }

    fun hide(notification: UnifiedNotification) {
        launchAction("Failed to dismiss notification") {
            notificationRepository.hide(notification)
        }
    }

    fun decline(notification: UnifiedNotification) {
        // VRChat models declining a V1 friend request as hiding its notification.
        launchAction("Failed to decline friend request") {
            notificationRepository.hide(notification)
        }
    }

    fun consumeError(errorId: Long) {
        state.update { current ->
            if (current.errorId == errorId) current.copy(error = null) else current
        }
    }

    private fun publishError(message: String) {
        state.update { it.withError(message) }
    }

    private fun launchAction(fallbackMessage: String, action: suspend () -> Unit) {
        scope.launch {
            recoverableResult(action).exceptionOrNull()?.let { failure ->
                publishError(failure.message ?: fallbackMessage)
            }
        }
    }
}

internal class InviteResponseController(
    private val scope: CoroutineScope,
    private val notificationRepository: NotificationRepository,
    private val inviteMessageRepository: InviteMessageRepository,
    private val state: MutableStateFlow<NotificationsActionState>,
) {
    private var dialogJob: Job? = null
    private var templateGeneration = 0L
    private var nextRequestId = 0L
    private val sendMutex = Mutex()

    fun open(notification: UnifiedNotification) {
        val messageType = notification.kind.inviteMessageType ?: return
        load(
            InviteResponseDialogState(
                requestId = ++nextRequestId,
                notification = notification,
                title = inviteDialogTitle(notification.kind),
                messageType = messageType,
                isLoading = true,
            ),
        )
    }

    fun refresh() {
        val dialog = state.value.inviteResponseDialog ?: return
        if (dialog.isSending) return
        load(dialog.copy(isLoading = true))
    }

    fun dismiss() {
        close()
    }

    fun send(requestId: Long, template: InviteMessageTemplate) {
        val dialog = markSending(requestId, template) ?: return
        scope.launch {
            sendMutex.withLock {
                val failure = recoverableResult {
                    notificationRepository.sendInviteResponse(
                        notification = dialog.notification,
                        responseSlot = template.slot,
                    )
                }.exceptionOrNull()
                if (failure == null) {
                    close(requestId)
                } else {
                    reportSendFailure(requestId, failure)
                }
            }
        }
    }

    private fun load(dialog: InviteResponseDialogState) {
        dialogJob?.cancel()
        val generation = ++templateGeneration
        state.update { it.copy(inviteResponseDialog = dialog) }
        dialogJob = scope.launch {
            val result = recoverableResult {
                inviteMessageRepository.getMessages(dialog.messageType)
            }
            if (generation != templateGeneration) return@launch
            result.fold(
                onSuccess = { templates ->
                    state.update { current ->
                        if (current.inviteResponseDialog?.requestId == dialog.requestId) {
                            current.copy(
                                inviteResponseDialog = dialog.copy(
                                    templates = templates,
                                    isLoading = false,
                                ),
                            )
                        } else {
                            current
                        }
                    }
                },
                onFailure = { failure ->
                    state.update { current ->
                        if (current.inviteResponseDialog?.requestId == dialog.requestId) {
                            current.copy(
                                inviteResponseDialog = dialog.copy(
                                    templates = emptyList(),
                                    isLoading = false,
                                ),
                            ).withError(failure.message ?: "Failed to load saved invite responses")
                        } else {
                            current
                        }
                    }
                },
            )
        }
    }

    private fun close(expectedRequestId: Long? = null) {
        val current = state.value.inviteResponseDialog
        if (expectedRequestId != null && current?.requestId != expectedRequestId) return
        templateGeneration++
        dialogJob?.cancel()
        dialogJob = null
        state.update { action ->
            if (expectedRequestId == null || action.inviteResponseDialog?.requestId == expectedRequestId) {
                action.copy(inviteResponseDialog = null)
            } else {
                action
            }
        }
    }

    private fun markSending(requestId: Long, template: InviteMessageTemplate): InviteResponseDialogState? {
        while (true) {
            val current = state.value
            val dialog = current.inviteResponseDialog
                ?.takeIf { it.requestId == requestId && !it.isSending && template in it.templates }
                ?: return null
            val sending = current.copy(inviteResponseDialog = dialog.copy(isSending = true))
            if (state.compareAndSet(current, sending)) return dialog
        }
    }

    private fun reportSendFailure(requestId: Long, failure: Throwable) {
        state.update { current ->
            val dialog = current.inviteResponseDialog
            if (dialog?.requestId == requestId) {
                current.copy(inviteResponseDialog = dialog.copy(isSending = false))
                    .withError(failure.message ?: "Failed to send invite response")
            } else {
                current
            }
        }
    }
}

private fun NotificationsActionState.withError(message: String): NotificationsActionState =
    copy(error = message, errorId = errorId + 1)

private fun inviteDialogTitle(kind: NotificationKind): String = when (kind) {
    NotificationKind.INVITE -> "Respond to Invite"
    NotificationKind.REQUEST_INVITE -> "Respond to Invite Request"
    else -> "Send Response"
}

private suspend fun <T> recoverableResult(action: suspend () -> T): Result<T> {
    val result = runCatching { action() }
    result.exceptionOrNull()?.let { failure ->
        if (failure is CancellationException || failure !is Exception) throw failure
    }
    return result
}
