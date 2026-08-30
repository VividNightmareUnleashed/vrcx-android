package com.vrcx.android.data.repository

import com.vrcx.android.data.db.dao.FriendNotifyDao
import com.vrcx.android.data.db.dao.disable
import com.vrcx.android.data.db.dao.enable
import com.vrcx.android.data.db.dao.isEnabled
import com.vrcx.android.data.db.entity.FriendLogHistoryEntity
import com.vrcx.android.data.model.FriendContext
import com.vrcx.android.data.model.FriendTransition
import com.vrcx.android.data.websocket.PipelineEvent
import com.vrcx.android.di.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class FriendRepository @Inject internal constructor(
    private val snapshotCoordinator: FriendSnapshotCoordinator,
    private val eventProcessor: FriendEventProcessor,
    private val favoriteRepository: FavoriteRepository,
    private val friendNotifyDao: FriendNotifyDao,
    private val friendLogSynchronizer: FriendLogSynchronizer,
    accountScope: AccountScope,
    @IoDispatcher ioDispatcher: CoroutineDispatcher,
) : AccountScoped {
    private data class ActiveFriendsLoad(val token: AccountScope.Token, val deferred: Deferred<Unit>)

    private val account = accountScope.bindTo(this)
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val _favoriteFriendIds = MutableStateFlow<Set<String>>(emptySet())
    val favoriteFriendIds: StateFlow<Set<String>> = _favoriteFriendIds.asStateFlow()

    private val _notifyEnabledIds = MutableStateFlow<Set<String>>(emptySet())
    val notifyEnabledIds: StateFlow<Set<String>> = _notifyEnabledIds.asStateFlow()

    private val state = FriendRuntimeState(account, scope) { enabledIds ->
        _notifyEnabledIds.value = enabledIds
    }
    val friends: StateFlow<Map<String, FriendContext>> = state.friends
    val friendTransitions: SharedFlow<AccountScopedEvent<FriendTransition>> = state.friendTransitions

    internal val pendingOfflineIds: MutableSet<String> get() = state.pendingOfflineIds
    internal var offlineDelayMs: Long
        get() = state.offlineDelayMs
        set(value) {
            state.offlineDelayMs = value
        }

    private val friendsLoadMutex = Mutex()
    private var activeFriendsLoad: ActiveFriendsLoad? = null

    init {
        scope.launch {
            favoriteRepository.favorites.collect { favorites ->
                _favoriteFriendIds.value = favorites
                    .filter { it.type == "friend" }
                    .map { it.favoriteId }
                    .toSet()
            }
        }
    }

    /** Clears only state owned by the account that is ending. */
    override fun clearRuntimeState() {
        state.clear()
        _favoriteFriendIds.value = emptySet()
        _notifyEnabledIds.value = emptySet()
    }

    suspend fun loadFriendsList() {
        loadFriendsList(account.current())
    }

    suspend fun loadFriendsList(token: AccountScope.Token) {
        if (token.ownerUserId.isEmpty() || !account.isCurrent(token)) return
        val deferred = friendsLoadMutex.withLock {
            activeFriendsLoad
                ?.takeIf { it.token == token && it.deferred.isActive }
                ?.deferred
                ?: scope.async { snapshotCoordinator.load(state, token) }.also {
                    activeFriendsLoad = ActiveFriendsLoad(token, it)
                }
        }
        try {
            deferred.await()
        } finally {
            // A cancelled waiter must not detach the still-running shared load.
            if (deferred.isCompleted) {
                withContext(NonCancellable) {
                    friendsLoadMutex.withLock {
                        if (activeFriendsLoad?.deferred === deferred) activeFriendsLoad = null
                    }
                }
            }
        }
    }

    /** Replaces any pre-gap snapshot with one whose first request starts after the gap. */
    internal suspend fun resynchronize(token: AccountScope.Token) {
        if (token.ownerUserId.isEmpty() || !account.isCurrent(token)) return
        val preGapLoad = friendsLoadMutex.withLock {
            activeFriendsLoad
                ?.takeIf { it.token == token && it.deferred.isActive }
                ?.deferred
                ?.also { activeFriendsLoad = null }
        }
        preGapLoad?.cancelAndJoin()
        account.ensureCurrent(token)
        loadFriendsList(token)
    }

    suspend fun toggleFriendNotify(friendUserId: String): Boolean {
        val token = account.current()
        val ownerId = token.ownerUserId
        if (ownerId.isEmpty() || !account.isCurrent(token)) return false
        val newEnabled = !friendNotifyDao.isEnabled(ownerId, friendUserId)
        account.ensureCurrent(token)
        if (newEnabled) {
            friendNotifyDao.enable(ownerId, friendUserId)
        } else {
            friendNotifyDao.disable(ownerId, friendUserId)
        }
        account.publishOrAbort(token) {
            _notifyEnabledIds.value = if (newEnabled) {
                _notifyEnabledIds.value + friendUserId
            } else {
                _notifyEnabledIds.value - friendUserId
            }
        }
        return newEnabled
    }

    fun observeNotifyEnabledIds(ownerUserId: String): Flow<Set<String>> =
        friendNotifyDao.getEnabledFriendIds(ownerUserId).map { it.toSet() }

    fun friendLogHistory(ownerUserId: String, limit: Int): Flow<List<FriendLogHistoryEntity>> =
        friendLogSynchronizer.history(ownerUserId, limit)

    suspend fun handleEvent(event: PipelineEvent, token: AccountScope.Token) {
        if (token.ownerUserId.isEmpty() || !account.isCurrent(token)) return
        eventProcessor.handle(state, event, token)
    }

    internal suspend fun handleEvent(event: PipelineEvent) {
        handleEvent(event, account.current())
    }
}
