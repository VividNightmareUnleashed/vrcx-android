package com.vrcx.android.data.repository

import com.vrcx.android.data.api.BulkPaginator
import com.vrcx.android.data.api.RequestDeduplicator
import com.vrcx.android.data.api.UserApi
import com.vrcx.android.data.api.model.VrcUser
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserRepository @Inject constructor(
    private val userApi: UserApi,
    private val dedup: RequestDeduplicator,
) {
    private val cacheLock = Any()
    private var accountGeneration = 0L
    private val cachedUsers = mutableMapOf<String, VrcUser>()

    fun cacheUser(user: VrcUser) {
        synchronized(cacheLock) {
            cacheUserLocked(user)
        }
    }

    fun cacheUsers(users: Iterable<VrcUser>) {
        synchronized(cacheLock) {
            users.forEach(::cacheUserLocked)
        }
    }

    suspend fun getUser(userId: String, forceRefresh: Boolean = false): VrcUser {
        val generation = synchronized(cacheLock) {
            if (!forceRefresh) cachedUsers[userId]?.let { return it }
            accountGeneration
        }
        val user = dedup.dedupGet("user:$userId") { userApi.getUser(userId) }
        synchronized(cacheLock) {
            if (generation == accountGeneration) cacheUserLocked(user)
        }
        return user
    }

    fun clearCache() {
        synchronized(cacheLock) {
            accountGeneration++
            cachedUsers.clear()
        }
    }

    private fun cacheUserLocked(user: VrcUser) {
        if (user.id.isNotEmpty()) cachedUsers[user.id] = user
    }

    suspend fun getMutualFriends(userId: String): List<VrcUser> = BulkPaginator.fetchAll(
        pageSize = 100,
    ) { offset, count ->
        userApi.getMutualFriends(userId = userId, n = count, offset = offset)
    }

    suspend fun saveUserNote(targetUserId: String, note: String): JsonElement =
        userApi.saveUserNote(mapOf("targetUserId" to targetUserId, "note" to note))
    suspend fun sendBoop(userId: String): JsonElement = userApi.sendBoop(userId)
}
