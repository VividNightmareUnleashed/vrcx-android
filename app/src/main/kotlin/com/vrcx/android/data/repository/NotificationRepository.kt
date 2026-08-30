package com.vrcx.android.data.repository

import com.vrcx.android.data.api.NotificationApi
import com.vrcx.android.data.db.dao.NotificationDao
import com.vrcx.android.data.websocket.PipelineEvent
import com.vrcx.android.di.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json

/** Public notification facade; state, refresh, events, actions, and storage each have one owner. */
@Singleton
class NotificationRepository internal constructor(
    notificationApi: NotificationApi,
    authRepository: AuthRepository,
    notificationDao: NotificationDao,
    json: Json,
    accountScope: AccountScope,
    storageConfig: NotificationStorageWorker.Config,
) : AccountScoped {
    @Inject
    constructor(
        notificationApi: NotificationApi,
        authRepository: AuthRepository,
        notificationDao: NotificationDao,
        json: Json,
        accountScope: AccountScope,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : this(
        notificationApi = notificationApi,
        authRepository = authRepository,
        notificationDao = notificationDao,
        json = json,
        accountScope = accountScope,
        storageConfig = NotificationStorageWorker.Config(
            scope = CoroutineScope(SupervisorJob() + ioDispatcher),
            capacity = NotificationRepositoryLimits.DEFAULT_STORAGE_CAPACITY,
        ),
    )

    private val account = accountScope.bindTo(this)
    private val state = NotificationInboxState(account)
    private val refreshCoordinator: NotificationRefreshCoordinator by lazy {
        NotificationRefreshCoordinator(
            notificationApi = notificationApi,
            notificationDao = notificationDao,
            json = json,
            account = account,
            state = state,
            storage = storage,
        )
    }
    private val storage: NotificationStorageWorker = NotificationStorageWorker(
        notificationDao = notificationDao,
        json = json,
        accountScope = account,
        config = storageConfig,
        maxCachedNotifications = NotificationRepositoryLimits.MAX_CACHED,
        requestResync = { token: AccountScope.Token ->
            refreshCoordinator.refresh(expectedToken = token, forceFullResync = true)
        },
    )
    private val eventCoordinator = NotificationEventCoordinator(
        account = account,
        state = state,
        storage = storage,
        decoder = NotificationPipelineDecoder(json),
    )
    private val actionCoordinator = NotificationActionCoordinator(
        notificationApi = notificationApi,
        authRepository = authRepository,
        account = account,
        state = state,
        storage = storage,
    )

    val unifiedNotifications: StateFlow<List<UnifiedNotification>> = state.unifiedNotifications
    internal val currentEventToken: AccountScope.Token?
        get() = account.notificationToken()

    suspend fun restoreNotifications() {
        refreshCoordinator.restore()
    }

    override fun clearRuntimeState() {
        state.clearRuntimeState()
    }

    suspend fun loadNotifications() {
        refreshCoordinator.refresh()
    }

    internal suspend fun resynchronize(token: AccountScope.Token) {
        refreshCoordinator.refresh(expectedToken = token, forceFullResync = true)
    }

    fun handleEvent(event: PipelineEvent, token: AccountScope.Token): AccountScopedEvent<UnifiedNotification>? =
        eventCoordinator.handle(event, token)

    suspend fun sendInviteToUser(userId: String, messageSlot: Int? = null) {
        actionCoordinator.sendInviteToUser(userId, messageSlot)
    }

    suspend fun sendInviteResponse(notification: UnifiedNotification, responseSlot: Int) {
        actionCoordinator.sendInviteResponse(notification, responseSlot)
    }

    suspend fun performPrimaryAction(notification: UnifiedNotification) {
        actionCoordinator.performPrimaryAction(notification)
    }

    suspend fun respondToNotification(notification: UnifiedNotification, responseType: String) {
        actionCoordinator.respondToNotification(notification, responseType)
    }

    suspend fun hide(notification: UnifiedNotification) {
        actionCoordinator.hide(notification)
    }
}

internal fun NotificationRepository.handleEvent(event: PipelineEvent): AccountScopedEvent<UnifiedNotification>? =
    currentEventToken?.let { token -> handleEvent(event, token) }
