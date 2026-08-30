package com.vrcx.android.ui.screen.notifications

import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.repository.InviteMessageRepository
import com.vrcx.android.data.repository.InviteMessageTemplate
import com.vrcx.android.data.repository.InviteMessageType
import com.vrcx.android.data.repository.NotificationCategory
import com.vrcx.android.data.repository.NotificationCategoryCount
import com.vrcx.android.data.repository.NotificationCategoryFilter
import com.vrcx.android.data.repository.NotificationRepository
import com.vrcx.android.data.repository.UnifiedNotification
import com.vrcx.android.data.repository.notificationCategoryOf
import com.vrcx.android.data.repository.notificationTypeLabel
import com.vrcx.android.ui.common.LoadState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

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

internal data class NotificationCriteria(
    val category: NotificationCategoryFilter = NotificationCategoryFilter.ALL,
    val types: Set<String> = emptySet(),
)

internal data class NotificationLoad(val state: LoadState<Unit> = LoadState.NotLoaded, val errorId: Long = 0)

private fun LoadState<Unit>.withNotificationData(hasData: Boolean): LoadState<Unit> = when {
    !hasData -> this
    this is LoadState.Loading -> LoadState.Loaded(Unit, isRefreshing = true)
    this is LoadState.Failed -> LoadState.Loaded(Unit, staleError = message)
    this is LoadState.NotLoaded -> LoadState.Loaded(Unit)
    else -> this
}

private fun buildNotificationsUiState(
    notifications: List<UnifiedNotification>,
    criteria: NotificationCriteria,
    load: NotificationLoad,
    action: NotificationsActionState,
): NotificationsUiState {
    val categoryCounts = IntArray(NotificationCategoryFilter.entries.size)
    val categoryNotifications = ArrayList<UnifiedNotification>(notifications.size)
    notifications.forEach { notification ->
        categoryCounts[NotificationCategoryFilter.ALL.ordinal]++
        val category = when (notificationCategoryOf(notification.type)) {
            NotificationCategory.FRIEND -> NotificationCategoryFilter.FRIEND
            NotificationCategory.GROUP -> NotificationCategoryFilter.GROUP
            NotificationCategory.OTHER -> NotificationCategoryFilter.OTHER
        }
        categoryCounts[category.ordinal]++
        if (criteria.category == NotificationCategoryFilter.ALL || criteria.category == category) {
            categoryNotifications += notification
        }
    }
    val visibleTypes = (categoryNotifications.map { it.type } + criteria.types)
        .distinct()
        .sortedBy(::notificationTypeLabel)
    return NotificationsUiState(
        notifications = if (criteria.types.isEmpty()) {
            categoryNotifications
        } else {
            categoryNotifications.filter { it.type in criteria.types }
        },
        categoryCounts = NotificationCategoryFilter.entries.map { filter ->
            NotificationCategoryCount(filter = filter, count = categoryCounts[filter.ordinal])
        },
        visibleTypes = visibleTypes,
        selectedCategory = criteria.category,
        selectedTypes = criteria.types,
        loadState = load.state.withNotificationData(notifications.isNotEmpty()),
        loadErrorId = load.errorId,
        action = action,
    )
}

@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val notificationRepository: NotificationRepository,
    inviteMessageRepository: InviteMessageRepository,
) : NotificationsActionViewModel(
    notificationRepository = notificationRepository,
    inviteMessageRepository = inviteMessageRepository,
) {
    private val criteria = MutableStateFlow(NotificationCriteria())
    private val loadCoordinator = NotificationsLoadCoordinator(
        scope = viewModelScope,
        notificationRepository = notificationRepository,
    )
    val state: StateFlow<NotificationsUiState> = combine(
        notificationRepository.unifiedNotifications,
        criteria,
        loadCoordinator.state,
        notificationActionState,
        ::buildNotificationsUiState,
    ).stateIn(viewModelScope, SharingStarted.Eagerly, NotificationsUiState())

    init {
        loadCoordinator.start()
    }

    fun selectCategory(category: NotificationCategoryFilter) {
        criteria.value = NotificationCriteria(category = category)
    }

    fun toggleTypeFilter(type: String) {
        criteria.update { current ->
            current.copy(types = if (type in current.types) current.types - type else current.types + type)
        }
    }

    fun refresh() {
        loadCoordinator.refresh()
    }

    fun consumeLoadError(errorId: Long) {
        loadCoordinator.consumeError(errorId)
    }
}
