package com.vrcx.android.ui.screen.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.repository.InviteMessageRepository
import com.vrcx.android.data.repository.InviteMessageTemplate
import com.vrcx.android.data.repository.InviteMessageType
import com.vrcx.android.data.repository.NotificationCategoryCount
import com.vrcx.android.data.repository.NotificationCategoryFilter
import com.vrcx.android.data.repository.NotificationKind
import com.vrcx.android.data.repository.NotificationRepository
import com.vrcx.android.data.repository.UnifiedNotification
import com.vrcx.android.data.repository.matchesCategory
import com.vrcx.android.data.repository.notificationTypeLabel
import com.vrcx.android.ui.common.LoadState
import com.vrcx.android.ui.common.completeLoad
import com.vrcx.android.ui.common.failLoad
import com.vrcx.android.ui.common.isBusy
import com.vrcx.android.ui.common.settleLoad
import com.vrcx.android.ui.common.startLoad
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class InviteResponseDialogState(
    val requestId: Long,
    val notification: UnifiedNotification,
    val title: String,
    val messageType: InviteMessageType,
    val templates: List<InviteMessageTemplate> = emptyList(),
    val isLoading: Boolean = false,
    val isSending: Boolean = false,
)

data class NotificationsActionState(
    val error: String? = null,
    val errorId: Long = 0,
    val inviteResponseDialog: InviteResponseDialogState? = null,
)

private fun NotificationsActionState.withError(message: String): NotificationsActionState =
    copy(error = message, errorId = errorId + 1)

data class NotificationsUiState(
    val notifications: List<UnifiedNotification> = emptyList(),
    val categoryCounts: List<NotificationCategoryCount> = emptyList(),
    val visibleTypes: List<String> = emptyList(),
    val selectedCategory: NotificationCategoryFilter = NotificationCategoryFilter.ALL,
    val selectedTypes: Set<String> = emptySet(),
    val loadState: LoadState<Unit> = LoadState.NotLoaded,
    val loadErrorId: Long = 0,
    val action: NotificationsActionState = NotificationsActionState(),
)

private data class NotificationCriteria(
    val category: NotificationCategoryFilter = NotificationCategoryFilter.ALL,
    val types: Set<String> = emptySet(),
)

private data class NotificationLoad(val state: LoadState<Unit> = LoadState.NotLoaded, val errorId: Long = 0)

private fun LoadState<Unit>.withNotificationData(hasData: Boolean): LoadState<Unit> = when {
    !hasData -> this
    this is LoadState.Loading -> LoadState.Loaded(Unit, isRefreshing = true)
    this is LoadState.Failed -> LoadState.Loaded(Unit, staleError = message)
    this is LoadState.NotLoaded -> LoadState.Loaded(Unit)
    else -> this
}

@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val notificationRepository: NotificationRepository,
    private val inviteMessageRepository: InviteMessageRepository,
) : ViewModel() {
    private val criteria = MutableStateFlow(NotificationCriteria())
    private val load = MutableStateFlow(NotificationLoad())
    private val action = MutableStateFlow(NotificationsActionState())

    val state: StateFlow<NotificationsUiState> = combine(
        notificationRepository.unifiedNotifications,
        criteria,
        load,
        action,
    ) { notifications, criteria, load, action ->
        val categoryNotifications = notifications.filter { it.matchesCategory(criteria.category) }
        val visibleTypes = (categoryNotifications.map { it.type } + criteria.types)
            .distinct()
            .sortedBy(::notificationTypeLabel)
        NotificationsUiState(
            notifications = if (criteria.types.isEmpty()) {
                categoryNotifications
            } else {
                categoryNotifications.filter { it.type in criteria.types }
            },
            categoryCounts = NotificationCategoryFilter.entries.map { filter ->
                NotificationCategoryCount(
                    filter = filter,
                    count = if (filter == NotificationCategoryFilter.ALL) {
                        notifications.size
                    } else {
                        notifications.count { it.matchesCategory(filter) }
                    },
                )
            },
            visibleTypes = visibleTypes,
            selectedCategory = criteria.category,
            selectedTypes = criteria.types,
            loadState = load.state.withNotificationData(notifications.isNotEmpty()),
            loadErrorId = load.errorId,
            action = action,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, NotificationsUiState())

    private var inviteDialogJob: Job? = null
    private var inviteTemplateGeneration = 0L
    private var nextInviteRequestId = 0L
    private val inviteResponseMutex = Mutex()

    init {
        viewModelScope.launch {
            load.update { it.copy(state = it.state.startLoad()) }
            try {
                notificationRepository.restoreNotifications()
                if (notificationRepository.unifiedNotifications.value.isNotEmpty()) {
                    load.update { it.copy(state = it.state.completeLoad(Unit)) }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                load.update {
                    it.copy(
                        state = it.state.failLoad(e.message ?: "Failed to restore notifications"),
                        errorId = it.errorId + 1,
                    )
                }
            } finally {
                load.update { it.copy(state = it.state.settleLoad()) }
            }
            refresh()
        }
    }

    fun selectCategory(category: NotificationCategoryFilter) {
        criteria.value = NotificationCriteria(category = category)
    }

    fun toggleTypeFilter(type: String) {
        criteria.update { current ->
            current.copy(
                types = if (type in
                    current.types
                ) {
                    current.types - type
                } else {
                    current.types + type
                },
            )
        }
    }

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

    fun openInviteResponseDialog(notification: UnifiedNotification) {
        val messageType = inviteMessageTypeFor(notification) ?: return
        loadInviteResponseDialog(
            InviteResponseDialogState(
                requestId = ++nextInviteRequestId,
                notification = notification,
                title = inviteDialogTitleFor(notification),
                messageType = messageType,
                isLoading = true,
            ),
        )
    }

    fun refreshInviteResponseDialog() {
        val state = action.value.inviteResponseDialog ?: return
        if (state.isSending) return
        loadInviteResponseDialog(state.copy(isLoading = true))
    }

    fun dismissInviteResponseDialog() = closeInviteResponseDialog()

    private fun closeInviteResponseDialog(expectedRequestId: Long? = null) {
        val current = action.value.inviteResponseDialog
        if (expectedRequestId != null && current?.requestId != expectedRequestId) return
        inviteTemplateGeneration++
        inviteDialogJob?.cancel()
        inviteDialogJob = null
        action.update { currentAction ->
            if (expectedRequestId == null ||
                currentAction.inviteResponseDialog?.requestId == expectedRequestId
            ) {
                currentAction.copy(inviteResponseDialog = null)
            } else {
                currentAction
            }
        }
    }

    fun sendInviteResponse(requestId: Long, template: InviteMessageTemplate) {
        val dialog = markInviteResponseSending(requestId, template) ?: return
        viewModelScope.launch {
            inviteResponseMutex.withLock {
                try {
                    notificationRepository.sendInviteResponse(
                        notification = dialog.notification,
                        responseSlot = template.slot,
                    )
                    closeInviteResponseDialog(requestId)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    action.update { current ->
                        val currentDialog = current.inviteResponseDialog
                        if (currentDialog?.requestId == requestId) {
                            current.copy(
                                inviteResponseDialog = currentDialog.copy(isSending = false),
                            ).withError(error.message ?: "Failed to send invite response")
                        } else {
                            current
                        }
                    }
                }
            }
        }
    }

    private fun markInviteResponseSending(
        requestId: Long,
        template: InviteMessageTemplate,
    ): InviteResponseDialogState? {
        while (true) {
            val current = action.value
            val dialog = current.inviteResponseDialog
                ?.takeIf { it.requestId == requestId && !it.isSending && template in it.templates }
                ?: return null
            val sending = current.copy(inviteResponseDialog = dialog.copy(isSending = true))
            if (action.compareAndSet(current, sending)) return dialog
        }
    }

    fun hide(notification: UnifiedNotification) {
        launchAction("Failed to dismiss notification") {
            notificationRepository.hide(notification)
        }
    }

    /**
     * Explicit decline path for friend requests. Calls the same hide endpoint
     * (which is how VRChat declines V1 friendRequest notifications server-side)
     * but surfaces the intent so users aren't guessing whether "Dismiss" leaves
     * the request open.
     */
    fun declineFriendRequest(notification: UnifiedNotification) {
        launchAction("Failed to decline friend request") {
            notificationRepository.hide(notification)
        }
    }

    private fun loadInviteResponseDialog(state: InviteResponseDialogState) {
        inviteDialogJob?.cancel()
        val generation = ++inviteTemplateGeneration
        action.update { it.copy(inviteResponseDialog = state) }
        inviteDialogJob = viewModelScope.launch {
            try {
                val templates = inviteMessageRepository.getMessages(state.messageType)
                if (generation != inviteTemplateGeneration) return@launch
                action.update {
                    if (it.inviteResponseDialog?.requestId == state.requestId) {
                        it.copy(
                            inviteResponseDialog = state.copy(
                                templates = templates,
                                isLoading = false,
                            ),
                        )
                    } else {
                        it
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (error: Exception) {
                if (generation != inviteTemplateGeneration) return@launch
                action.update {
                    if (it.inviteResponseDialog?.requestId == state.requestId) {
                        it.copy(
                            inviteResponseDialog = state.copy(
                                templates = emptyList(),
                                isLoading = false,
                            ),
                        ).withError(error.message ?: "Failed to load saved invite responses")
                    } else {
                        it
                    }
                }
            }
        }
    }

    private fun inviteMessageTypeFor(notification: UnifiedNotification): InviteMessageType? =
        notification.kind.inviteMessageType

    private fun inviteDialogTitleFor(notification: UnifiedNotification): String = when (notification.kind) {
        NotificationKind.INVITE -> "Respond to Invite"
        NotificationKind.REQUEST_INVITE -> "Respond to Invite Request"
        else -> "Send Response"
    }

    fun refresh() {
        if (load.value.state.isBusy) return
        load.update { it.copy(state = it.state.startLoad()) }
        viewModelScope.launch {
            try {
                notificationRepository.loadNotifications()
                load.update { it.copy(state = it.state.completeLoad(Unit)) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                load.update {
                    it.copy(
                        state = it.state.failLoad(e.message ?: "Failed to load notifications"),
                        errorId = it.errorId + 1,
                    )
                }
            } finally {
                load.update { it.copy(state = it.state.settleLoad()) }
            }
        }
    }

    private fun launchAction(fallbackMessage: String, action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                action()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                publishActionError(error.message ?: fallbackMessage)
            }
        }
    }

    private fun publishActionError(message: String) {
        action.update { it.withError(message) }
    }

    fun consumeActionError(errorId: Long) {
        action.update { current ->
            if (current.errorId == errorId) current.copy(error = null) else current
        }
    }

    fun consumeLoadError(errorId: Long) {
        load.update { current ->
            if (current.errorId != errorId) return@update current
            val consumedState = when {
                current.state is LoadState.Loaded -> current.state.copy(staleError = null)

                current.state is LoadState.Failed && notificationRepository.unifiedNotifications.value.isNotEmpty() ->
                    LoadState.Loaded(Unit)

                else -> current.state
            }
            current.copy(state = consumedState)
        }
    }
}
