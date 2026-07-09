package com.vrcx.android.data.repository

import com.vrcx.android.data.api.RequestDeduplicator
import com.vrcx.android.data.api.UserApi
import com.vrcx.android.data.api.model.VrcUser
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserRepository @Inject constructor(
    private val userApi: UserApi,
    private val dedup: RequestDeduplicator,
) {
    private val cachedUsers = ConcurrentHashMap<String, VrcUser>()

    fun getCachedUser(userId: String): VrcUser? = cachedUsers[userId]

    fun cacheUser(user: VrcUser) {
        if (user.id.isNotEmpty()) {
            cachedUsers[user.id] = user
        }
    }

    suspend fun getUser(userId: String, forceRefresh: Boolean = false): VrcUser {
        if (!forceRefresh) {
            cachedUsers[userId]?.let { return it }
        }
        val user = dedup.dedupGet("user:$userId") { userApi.getUser(userId) }
        cacheUser(user)
        return user
    }

    fun clearCache() {
        cachedUsers.clear()
    }

    suspend fun getMutualCounts(userId: String): JsonElement = userApi.getMutualCounts(userId)
    suspend fun getMutualFriends(userId: String): List<VrcUser> = userApi.getMutualFriends(userId)
    suspend fun getMutualGroups(userId: String): JsonElement = userApi.getMutualGroups(userId)
    suspend fun getUserNotes(): JsonElement = userApi.getUserNotes()
    suspend fun saveUserNote(targetUserId: String, note: String): JsonElement =
        userApi.saveUserNote(mapOf("targetUserId" to targetUserId, "note" to note))
    suspend fun sendBoop(userId: String): JsonElement = userApi.sendBoop(userId)
    suspend fun reportUser(userId: String, contentType: String, reason: String, type: String): JsonElement =
        userApi.reportUser(userId, mapOf("contentType" to contentType, "reason" to reason, "type" to type))
}
