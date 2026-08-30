package com.vrcx.android.service

import com.vrcx.android.data.repository.AccountScope
import com.vrcx.android.data.repository.AccountScopedEvent
import com.vrcx.android.data.repository.UnifiedNotification
import com.vrcx.android.data.websocket.PipelineEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Applies one pipeline frame across capability owners in account-safe order. */
internal class PipelineEventRouter(
    private val repositories: PipelineRepositories,
    private val state: PipelineRuntimeState,
    private val stateResynchronizer: PipelineStateResynchronizer,
    private val notificationHelper: NotificationHelper,
) {
    suspend fun route(event: PipelineEvent, origin: AccountScope.Token, scope: CoroutineScope) {
        when {
            !state.isCurrent(scope, origin) -> Unit
            event === PipelineEvent.StreamGap -> recover(origin, scope)
            else -> routeCapabilities(event, origin, scope)
        }
    }

    private suspend fun routeCapabilities(event: PipelineEvent, origin: AccountScope.Token, scope: CoroutineScope) {
        containPipelineFailure("friends") { repositories.friends.handleEvent(event, origin) }
        var notification: AccountScopedEvent<UnifiedNotification>? = null
        if (state.isCurrent(scope, origin)) {
            notification = containPipelineFailure("notifications") {
                repositories.notifications.handleEvent(event, origin)
            }
        }
        if (state.isCurrent(scope, origin)) {
            containPipelineFailure("session") { repositories.auth.handleEvent(event, origin) }
        }
        if (state.isCurrent(scope, origin)) {
            containPipelineFailure("groups") { repositories.groups.handleEvent(event, origin) }
        }
        if (state.isCurrent(scope, origin)) {
            containPipelineFailure("gallery") { handleContentRefresh(event, origin, scope) }
        }
        dispatch(notification, origin, scope)
    }

    private suspend fun recover(origin: AccountScope.Token, scope: CoroutineScope) {
        val recovered = retryPipelineStateRecovery(
            isCurrent = { state.isCurrent(scope, origin) },
            recoverCore = { stateResynchronizer.resynchronizeCore(origin) },
        )
        val connection = if (recovered) {
            state.withPipeline(scope) {
                val currentSocket = socket
                if (currentSocket != null && authToken != null) currentSocket else null
            }
        } else {
            null
        }
        if (connection != null &&
            state.socketAction(scope, origin, connection, PipelineSocketAction.RECOVER)
        ) {
            scope.launch {
                if (state.isCurrent(scope, origin)) {
                    stateResynchronizer.resynchronizeGallery(origin)
                }
            }
        }
    }

    private fun handleContentRefresh(event: PipelineEvent, origin: AccountScope.Token, scope: CoroutineScope) {
        val contentType = (event as? PipelineEvent.ContentRefresh)
            ?.contentObject()
            ?.stringOrNull("contentType")
        if (contentType != null) {
            scope.launch {
                containPipelineFailure("gallery refresh") {
                    repositories.gallery.handleContentRefresh(contentType, origin.ownerUserId, origin)
                }
            }
        }
    }

    private fun dispatch(
        notification: AccountScopedEvent<UnifiedNotification>?,
        origin: AccountScope.Token,
        scope: CoroutineScope,
    ) {
        val settings = state.withPipeline(scope) { notificationSettings }
        if (notification != null && settings != null) {
            consumeAccountScopedPipelineEvent(repositories.accountScope, origin, notification) { value ->
                containPipelineFailure("system notifications") {
                    dispatchNotification(
                        helper = notificationHelper,
                        notification = value,
                        notifyInvite = settings.policy.invites,
                        notifyFriendRequest = settings.policy.friendRequests,
                        notifyGeneral = settings.policy.general,
                    )
                }
            }
        }
    }
}

/** Null rather than a throw when VRChat sends the other shape for a field. */
private fun PipelineEvent.contentObject(): JsonObject? = content as? JsonObject

private fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf {
    it !is JsonNull
}?.content
