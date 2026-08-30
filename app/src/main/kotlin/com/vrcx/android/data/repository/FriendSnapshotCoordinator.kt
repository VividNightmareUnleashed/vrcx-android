package com.vrcx.android.data.repository

import com.vrcx.android.data.db.dao.FriendNotifyDao
import com.vrcx.android.data.util.runIgnoringFailure
import javax.inject.Inject

/** Reconciles a REST snapshot and its ordered account-scoped follow-up work. */
internal class FriendSnapshotCoordinator @Inject constructor(
    private val snapshotLoader: FriendSnapshotLoader,
    private val userRepository: UserRepository,
    private val favoriteRepository: FavoriteRepository,
    private val friendNotifyDao: FriendNotifyDao,
    private val friendLogSynchronizer: FriendLogSynchronizer,
    private val activityRecorder: FriendActivityRecorder,
) {
    suspend fun load(state: FriendRuntimeState, token: AccountScope.Token) {
        val account = state.account
        val ownerId = token.ownerUserId
        val revision = state.snapshotRevision()
        activityRecorder.resetDedupe(token)
        val friendMap = snapshotLoader.load { account.ensureCurrent(token) }
        val committed = state.commitSnapshot(token, friendMap, revision)
        if (committed != null) {
            val cached = account.publishIfCurrent(token) {
                userRepository.cacheUsers(committed.values.mapNotNull { it.ref })
            }
            if (cached) {
                account.ensureCurrent(token)
                friendLogSynchronizer.synchronize(ownerId, committed)
                account.ensureCurrent(token)
                runIgnoringFailure { favoriteRepository.loadFavorites(type = "friend") }
                if (account.isCurrent(token)) {
                    runIgnoringFailure {
                        val enabledIds = friendNotifyDao.getEnabledFriendIdsSnapshot(ownerId).toSet()
                        state.publishNotifyIds(token, enabledIds)
                    }
                }
            }
        }
    }
}
