package com.vrcx.android.data.repository

import com.vrcx.android.data.api.model.VrcUser
import com.vrcx.android.data.db.accountScopedKey
import com.vrcx.android.data.db.dao.FriendLogDao
import com.vrcx.android.data.db.entity.FriendLogCurrentEntity
import com.vrcx.android.data.db.entity.FriendLogHistoryEntity
import com.vrcx.android.data.model.FriendContext
import com.vrcx.android.data.model.TrustRank
import java.time.Instant
import javax.inject.Inject

internal class FriendLogSynchronizer @Inject constructor(
    private val friendLogDao: FriendLogDao,
) {
    private data class Snapshot(
        val compositeId: String,
        val displayName: String,
        val trustLevel: String,
        val friendNumber: Int,
    )

    suspend fun synchronize(ownerId: String, friends: Map<String, FriendContext>) {
        val currentEntries = friendLogDao.getCurrentFriends(ownerId)
        if (currentEntries.isEmpty()) {
            friendLogDao.insertCurrent(
                friends.values.map { friend ->
                    currentEntry(
                        ownerId = ownerId,
                        userId = friend.id,
                        displayName = friend.ref?.displayName ?: friend.name,
                        tags = friend.ref?.tags.orEmpty(),
                        friendNumber = 0,
                    )
                },
            )
            return
        }

        val currentById = currentEntries.associateBy(FriendLogCurrentEntity::odUserId)
        val seenIds = HashSet<String>(friends.size)
        var nextFriendNumber = (currentEntries.maxOfOrNull { it.friendNumber } ?: 0) + 1

        friends.values.forEach { friend ->
            val compositeId = compositeId(ownerId, friend.id)
            val displayName = friend.ref?.displayName ?: friend.name
            val tags = friend.ref?.tags.orEmpty()
            val existing = currentById[compositeId]
            seenIds += compositeId

            if (existing == null) {
                val snapshot = Snapshot(
                    compositeId = compositeId,
                    displayName = displayName,
                    trustLevel = trustLevel(tags),
                    friendNumber = nextFriendNumber++,
                )
                insertHistory(ownerId, "Friend", snapshot)
                friendLogDao.insertCurrent(snapshot.toCurrent(ownerId))
            } else {
                recordChanges(ownerId, existing, friend.id, displayName, tags)
            }
        }

        currentEntries.filter { it.odUserId !in seenIds }.forEach { missing ->
            insertHistory(
                ownerId = ownerId,
                type = "Unfriend",
                snapshot = Snapshot(
                    compositeId = missing.odUserId,
                    displayName = missing.odDisplayName,
                    trustLevel = missing.trustLevel,
                    friendNumber = missing.friendNumber,
                ),
            )
            friendLogDao.deleteCurrent(missing.odUserId)
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
        val previousTrustLevel = trustLevel(previous.ref?.tags.orEmpty())
        if (previousDisplayName == displayName && previousTrustLevel == trustLevel(tags)) return

        val compositeId = compositeId(ownerId, userId)
        val existing = friendLogDao.getCurrent(compositeId)
        if (existing == null) {
            friendLogDao.insertCurrent(
                currentEntry(
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

    suspend fun recordAdded(ownerId: String, userId: String, user: VrcUser?) {
        val compositeId = compositeId(ownerId, userId)
        if (friendLogDao.getCurrent(compositeId) != null) return

        val snapshot = Snapshot(
            compositeId = compositeId,
            displayName = user?.displayName ?: userId,
            trustLevel = trustLevel(user?.tags.orEmpty()),
            friendNumber = (friendLogDao.getMaxFriendNumber(ownerId) ?: 0) + 1,
        )
        insertHistory(ownerId, "Friend", snapshot)
        friendLogDao.insertCurrent(snapshot.toCurrent(ownerId))
    }

    suspend fun recordRemoved(ownerId: String, userId: String) {
        val compositeId = compositeId(ownerId, userId)
        val existing = friendLogDao.getCurrent(compositeId) ?: return
        insertHistory(
            ownerId = ownerId,
            type = "Unfriend",
            snapshot = Snapshot(
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
        val snapshot = Snapshot(
            compositeId = compositeId(ownerId, userId),
            displayName = displayName,
            trustLevel = trustLevel(tags),
            friendNumber = existing.friendNumber,
        )
        if (existing.odDisplayName != displayName && existing.odDisplayName.isNotBlank()) {
            insertHistory(
                ownerId = ownerId,
                type = "DisplayName",
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
                type = "TrustLevel",
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
        type: String,
        snapshot: Snapshot,
        previousDisplayName: String = "",
        previousTrustLevel: String = "",
    ) {
        friendLogDao.insertHistory(
            FriendLogHistoryEntity(
                ownerUserId = ownerId,
                type = type,
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

    private fun currentEntry(
        ownerId: String,
        userId: String,
        displayName: String,
        tags: List<String>,
        friendNumber: Int,
    ) = FriendLogCurrentEntity(
        odUserId = compositeId(ownerId, userId),
        ownerUserId = ownerId,
        odDisplayName = displayName,
        trustLevel = trustLevel(tags),
        friendNumber = friendNumber,
    )

    private fun Snapshot.toCurrent(ownerId: String) = FriendLogCurrentEntity(
        odUserId = compositeId,
        ownerUserId = ownerId,
        odDisplayName = displayName,
        trustLevel = trustLevel,
        friendNumber = friendNumber,
    )

    private fun compositeId(ownerId: String, userId: String) = accountScopedKey(ownerId, userId)

    private fun trustLevel(tags: List<String>) = TrustRank.fromTags(tags).label
}
