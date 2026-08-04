package com.vrcx.android.data.repository

import com.vrcx.android.data.api.AvatarApi
import com.vrcx.android.data.api.BulkPaginator
import com.vrcx.android.data.api.RequestDeduplicator
import com.vrcx.android.data.api.model.Avatar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AvatarRepository @Inject constructor(
    private val avatarApi: AvatarApi,
    private val dedup: RequestDeduplicator,
) {
    private data class CachedAvatar(val value: Avatar, val cachedAtMillis: Long)

    private val avatarCache = ConcurrentHashMap<String, CachedAvatar>()
    private val accountGeneration = AtomicLong(0)

    private val _myAvatars = MutableStateFlow<List<Avatar>>(emptyList())
    val myAvatars: StateFlow<List<Avatar>> = _myAvatars.asStateFlow()

    suspend fun loadMyAvatars() {
        val generation = accountGeneration.get()
        val avatars = getUserAvatars("me")
        if (generation == accountGeneration.get()) _myAvatars.value = avatars
    }

    fun clearRuntimeState() {
        accountGeneration.incrementAndGet()
        avatarCache.clear()
        _myAvatars.value = emptyList()
    }

    suspend fun selectAvatar(avatarId: String) {
        avatarApi.selectAvatar(avatarId)
    }

    /**
     * Pages `GET avatars?user=[userId]` to exhaustion and returns the result.
     * Read-only: it doesn't touch [myAvatars] or the cache, so it is safe to
     * call for another user's profile.
     *
     * Pass `"me"` for the logged-in user. For anyone else, what VRChat returns
     * is unverified — desktop VRCX doesn't use this endpoint for other users at
     * all, it queries a third-party avatar database by `authorId`. Confirm the
     * live response before relying on the non-self case.
     */
    suspend fun getUserAvatars(userId: String): List<Avatar> =
        BulkPaginator.fetchAll(pageSize = AVATAR_PAGE_SIZE) { offset, count ->
            avatarApi.getAvatars(user = userId, releaseStatus = "all", n = count, offset = offset)
        }

    suspend fun getAvatar(avatarId: String, forceRefresh: Boolean = false): Avatar {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            avatarCache[avatarId]?.takeIf { now - it.cachedAtMillis < AVATAR_CACHE_TTL_MS }
                ?.let { return it.value }
        }
        val generation = accountGeneration.get()
        val avatar = dedup.dedupGet("avatar:$avatarId") { avatarApi.getAvatar(avatarId) }
        if (generation == accountGeneration.get()) {
            avatarCache[avatarId] = CachedAvatar(avatar, System.currentTimeMillis())
        }
        return avatar
    }

    private companion object {
        const val AVATAR_PAGE_SIZE = 100
        const val AVATAR_CACHE_TTL_MS = 30L * 60L * 1000L
    }
}
