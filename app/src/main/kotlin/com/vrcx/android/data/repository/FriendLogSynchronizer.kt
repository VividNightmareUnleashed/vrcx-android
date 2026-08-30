package com.vrcx.android.data.repository

import androidx.room.withTransaction
import com.vrcx.android.data.api.model.VrcUser
import com.vrcx.android.data.db.VrcxDatabase
import com.vrcx.android.data.db.accountScopedKey
import com.vrcx.android.data.db.dao.FriendLogDao
import com.vrcx.android.data.db.entity.FriendLogCurrentEntity
import com.vrcx.android.data.db.entity.FriendLogHistoryEntity
import com.vrcx.android.data.model.FriendContext
import com.vrcx.android.data.model.TrustRank
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/**
 * The kinds of friend-log event, keyed by the [token] stored in
 * [FriendLogHistoryEntity.type]. Tokens are on disk — rename a constant freely,
 * never a token.
 */
enum class FriendLogEventType(val token: String, val label: String) {
    FRIEND("Friend", "Friend"),
    UNFRIEND("Unfriend", "Unfriend"),
    DISPLAY_NAME("DisplayName", "DisplayName"),
    TRUST_LEVEL("TrustLevel", "TrustLevel"),
    ;

    companion object {
        private val byToken = entries.associateBy { it.token }

        /** Null for a row written by a version that knew a kind this one does not. */
        fun fromToken(token: String?): FriendLogEventType? = byToken[token]
    }
}

internal class FriendLogSynchronizer @Inject constructor(
    private val database: VrcxDatabase,
    private val friendLogDao: FriendLogDao,
) {
    fun history(ownerId: String, limit: Int): Flow<List<FriendLogHistoryEntity>> =
        friendLogDao.getHistory(ownerId, limit)

    suspend fun synchronize(ownerId: String, friends: Map<String, FriendContext>) = database.withTransaction {
        val currentEntries = friendLogDao.getCurrentFriends(ownerId)
        if (currentEntries.isEmpty()) {
            friendLogDao.insertCurrent(
                friends.values.map { friend ->
                    friendLogCurrentEntry(
                        ownerId = ownerId,
                        userId = friend.id,
                        displayName = friend.ref?.displayName ?: friend.name,
                        tags = friend.ref?.tags.orEmpty(),
                        friendNumber = 0,
                    )
                },
            )
            return@withTransaction
        }

        val currentById = currentEntries.associateBy(FriendLogCurrentEntity::odUserId)
        val seenIds = HashSet<String>(friends.size)
        var nextFriendNumber = (currentEntries.maxOfOrNull { it.friendNumber } ?: 0) + 1

        friends.values.forEach { friend ->
            val compositeId = friendLogCompositeId(ownerId, friend.id)
            val displayName = friend.ref?.displayName ?: friend.name
            val tags = friend.ref?.tags.orEmpty()
            val existing = currentById[compositeId]
            seenIds += compositeId

            if (existing == null) {
                val snapshot = FriendLogSnapshot(
                    compositeId = compositeId,
                    displayName = displayName,
                    trustLevel = friendTrustLevel(tags),
                    friendNumber = nextFriendNumber++,
                )
                insertHistory(ownerId, FriendLogEventType.FRIEND, snapshot)
                friendLogDao.insertCurrent(snapshot.toCurrent(ownerId))
            } else {
                recordChanges(ownerId, existing, friend.id, displayName, tags)
            }
        }

        val missing = currentEntries.filter { it.odUserId !in seenIds }
        // A snapshot that came back short — a truncated sweep, or a friend who
        // moved between the online and offline lists while both were being
        // paged — looks exactly like a mass unfriend, and the rows below are
        // permanent. Real removals also arrive on the pipeline's friend-delete
        // path, and a later sync over a complete snapshot still catches the
        // rest, so an implausible removal set is left alone.
        if (missing.size > maxOf(MAX_TRUSTED_REMOVALS, currentEntries.size / FRIENDS_PER_TRUSTED_REMOVAL)) {
            return@withTransaction
        }

        missing.forEach { entry ->
            insertHistory(
                ownerId = ownerId,
                type = FriendLogEventType.UNFRIEND,
                snapshot = FriendLogSnapshot(
                    compositeId = entry.odUserId,
                    displayName = entry.odDisplayName,
                    trustLevel = entry.trustLevel,
                    friendNumber = entry.friendNumber,
                ),
            )
            friendLogDao.deleteCurrent(entry.odUserId)
        }
    }

    suspend fun synchronizeUpdatedFriend(
        ownerId: String,
        previous: FriendContext,
        userId: String,
        displayName: String,
        tags: List<String>,
    ) {
        val previousDisplayName = previous.ref?.displayName ?: previous.name
        val previousTrustLevel = friendTrustLevel(previous.ref?.tags.orEmpty())
        if (previousDisplayName == displayName && previousTrustLevel == friendTrustLevel(tags)) return

        database.withTransaction {
            val compositeId = friendLogCompositeId(ownerId, userId)
            val existing = friendLogDao.getCurrent(compositeId)
            if (existing == null) {
                friendLogDao.insertCurrent(
                    friendLogCurrentEntry(
                        ownerId = ownerId,
                        userId = userId,
                        displayName = displayName,
                        tags = tags,
                        friendNumber = (friendLogDao.getMaxFriendNumber(ownerId) ?: 0) + 1,
                    ),
                )
            } else {
                recordChanges(ownerId, existing, userId, displayName, tags)
            }
        }
    }

    suspend fun recordAdded(ownerId: String, userId: String, user: VrcUser?) = database.withTransaction {
        val compositeId = friendLogCompositeId(ownerId, userId)
        if (friendLogDao.getCurrent(compositeId) != null) return@withTransaction

        val snapshot = FriendLogSnapshot(
            compositeId = compositeId,
            displayName = user?.displayName ?: userId,
            trustLevel = friendTrustLevel(user?.tags.orEmpty()),
            friendNumber = (friendLogDao.getMaxFriendNumber(ownerId) ?: 0) + 1,
        )
        insertHistory(ownerId, FriendLogEventType.FRIEND, snapshot)
        friendLogDao.insertCurrent(snapshot.toCurrent(ownerId))
    }

    suspend fun recordRemoved(ownerId: String, userId: String) = database.withTransaction {
        val compositeId = friendLogCompositeId(ownerId, userId)
        val existing = friendLogDao.getCurrent(compositeId) ?: return@withTransaction
        insertHistory(
            ownerId = ownerId,
            type = FriendLogEventType.UNFRIEND,
            snapshot = FriendLogSnapshot(
                compositeId = compositeId,
                displayName = existing.odDisplayName,
                trustLevel = existing.trustLevel,
                friendNumber = existing.friendNumber,
            ),
        )
        friendLogDao.deleteCurrent(compositeId)
    }

    private suspend fun recordChanges(
        ownerId: String,
        existing: FriendLogCurrentEntity,
        userId: String,
        displayName: String,
        tags: List<String>,
    ) {
        val snapshot = FriendLogSnapshot(
            compositeId = friendLogCompositeId(ownerId, userId),
            displayName = displayName,
            trustLevel = friendTrustLevel(tags),
            friendNumber = existing.friendNumber,
        )
        if (existing.odDisplayName != displayName && existing.odDisplayName.isNotBlank()) {
            insertHistory(
                ownerId = ownerId,
                type = FriendLogEventType.DISPLAY_NAME,
                snapshot = snapshot,
                previousDisplayName = existing.odDisplayName,
            )
        }
        if (existing.trustLevel != snapshot.trustLevel &&
            existing.trustLevel.isNotBlank() &&
            snapshot.trustLevel.isNotBlank()
        ) {
            insertHistory(
                ownerId = ownerId,
                type = FriendLogEventType.TRUST_LEVEL,
                snapshot = snapshot,
                previousTrustLevel = existing.trustLevel,
            )
        }
        if (existing.odDisplayName != displayName || existing.trustLevel != snapshot.trustLevel) {
            friendLogDao.insertCurrent(snapshot.toCurrent(ownerId))
        }
    }

    private suspend fun insertHistory(
        ownerId: String,
        type: FriendLogEventType,
        snapshot: FriendLogSnapshot,
        previousDisplayName: String = "",
        previousTrustLevel: String = "",
    ) {
        friendLogDao.insertHistory(
            FriendLogHistoryEntity(
                ownerUserId = ownerId,
                type = type.token,
                odUserId = snapshot.compositeId,
                displayName = snapshot.displayName,
                previousDisplayName = previousDisplayName,
                trustLevel = snapshot.trustLevel,
                previousTrustLevel = previousTrustLevel,
                friendNumber = snapshot.friendNumber,
                createdAt = Instant.now().toString(),
            ),
        )
    }

    private companion object {
        const val FRIENDS_PER_TRUSTED_REMOVAL = 4

        /** Removals below this count are plausible no matter how small the friend list is. */
        const val MAX_TRUSTED_REMOVALS = 5
    }
}

private data class FriendLogSnapshot(
    val compositeId: String,
    val displayName: String,
    val trustLevel: String,
    val friendNumber: Int,
)

private fun friendLogCurrentEntry(
    ownerId: String,
    userId: String,
    displayName: String,
    tags: List<String>,
    friendNumber: Int,
) = FriendLogCurrentEntity(
    odUserId = friendLogCompositeId(ownerId, userId),
    ownerUserId = ownerId,
    odDisplayName = displayName,
    trustLevel = friendTrustLevel(tags),
    friendNumber = friendNumber,
)

private fun FriendLogSnapshot.toCurrent(ownerId: String) = FriendLogCurrentEntity(
    odUserId = compositeId,
    ownerUserId = ownerId,
    odDisplayName = displayName,
    trustLevel = trustLevel,
    friendNumber = friendNumber,
)

private fun friendLogCompositeId(ownerId: String, userId: String) = accountScopedKey(ownerId, userId)

private fun friendTrustLevel(tags: List<String>) = TrustRank.fromTags(tags).label
