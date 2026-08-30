package com.vrcx.android.ui.screen.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.repository.InviteMessageRepository
import com.vrcx.android.data.repository.InviteMessageTemplate
import com.vrcx.android.data.repository.NotificationRepository
import com.vrcx.android.data.repository.UnifiedNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Owns notification commands, invite-response dialogs, and their action feedback. */
open class NotificationsActionViewModel protected constructor(
    notificationRepository: NotificationRepository,
    inviteMessageRepository: InviteMessageRepository,
) : ViewModel() {
    private val mutableActionState = MutableStateFlow(NotificationsActionState())
    private val notificationActions = NotificationActionController(
        scope = viewModelScope,
        notificationRepository = notificationRepository,
        state = mutableActionState,
    )
    private val inviteResponses = InviteResponseController(
        scope = viewModelScope,
        notificationRepository = notificationRepository,
        inviteMessageRepository = inviteMessageRepository,
        state = mutableActionState,
    )
    protected val notificationActionState: StateFlow<NotificationsActionState> = mutableActionState.asStateFlow()

    fun performPrimaryAction(notification: UnifiedNotification) {
        notificationActions.performPrimaryAction(notification)
    }

    fun respond(notification: UnifiedNotification, responseType: String) {
        notificationActions.respond(notification, responseType)
    }

    fun openInviteResponseDialog(notification: UnifiedNotification) {
        inviteResponses.open(notification)
    }

    fun refreshInviteResponseDialog() {
        inviteResponses.refresh()
    }

    fun dismissInviteResponseDialog() {
        inviteResponses.dismiss()
    }

    fun sendInviteResponse(requestId: Long, template: InviteMessageTemplate) {
        inviteResponses.send(requestId, template)
    }

    fun hide(notification: UnifiedNotification) {
        notificationActions.hide(notification)
    }

    fun declineFriendRequest(notification: UnifiedNotification) {
        notificationActions.decline(notification)
    }

    fun consumeActionError(errorId: Long) {
        notificationActions.consumeError(errorId)
    }
}
