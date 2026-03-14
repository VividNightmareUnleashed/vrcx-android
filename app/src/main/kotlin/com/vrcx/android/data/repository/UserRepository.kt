package com.vrcx.android.data.repository

import com.vrcx.android.data.api.UserApi
import com.vrcx.android.data.api.model.VrcUser
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserRepository @Inject constructor(
    private val userApi: UserApi,
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
        val user = userApi.getUser(userId)
        cacheUser(user)
        return user
    }

    fun clearCache() {
        cachedUsers.clear()
    }
}
