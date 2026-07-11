package com.vrcx.android.data.repository

import com.vrcx.android.data.api.AvatarApi
import com.vrcx.android.data.api.BulkPaginator
import com.vrcx.android.data.api.RequestDeduplicator
import com.vrcx.android.data.api.model.Avatar
import com.vrcx.android.data.db.dao.CacheDao
import com.vrcx.android.data.db.entity.CacheAvatarEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AvatarRepository @Inject constructor(
    private val avatarApi: AvatarApi,
    private val dedup: RequestDeduplicator,
    private val cacheDao: CacheDao,
    private val json: Json,
) {
    private data class CachedAvatar(val value: Avatar, val cachedAtMillis: Long)

    private val avatarCache = ConcurrentHashMap<String, CachedAvatar>()
    private val accountGeneration = AtomicLong(0)
    private val legacyCachePurged = AtomicBoolean(false)

    private val _myAvatars = MutableStateFlow<List<Avatar>>(emptyList())
    val myAvatars: StateFlow<List<Avatar>> = _myAvatars.asStateFlow()

    suspend fun loadMyAvatars() {
        val generation = accountGeneration.get()
        val avatars = BulkPaginator.fetchAll(pageSize = AVATAR_PAGE_SIZE) { offset, count ->
            avatarApi.getAvatars(user = "me", releaseStatus = "all", n = count, offset = offset)
        }
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

    suspend fun getAvatar(avatarId: String, forceRefresh: Boolean = false): Avatar {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            avatarCache[avatarId]?.takeIf { now - it.cachedAtMillis < AVATAR_CACHE_TTL_MS }
                ?.let { return it.value }
        }
        purgeLegacyCacheOnce()

        val generation = accountGeneration.get()
        val avatar = dedup.dedupGet("avatar:$avatarId") { avatarApi.getAvatar(avatarId) }
        if (generation == accountGeneration.get()) {
            avatarCache[avatarId] = CachedAvatar(avatar, System.currentTimeMillis())
        }
        return avatar
    }

    private suspend fun purgeLegacyCacheOnce() {
        if (!legacyCachePurged.compareAndSet(false, true)) return
        runCatching { cacheDao.clearAvatarCache() }
            .onFailure { legacyCachePurged.set(false) }
    }

    private companion object {
        const val AVATAR_PAGE_SIZE = 100
        const val AVATAR_CACHE_TTL_MS = 30L * 60L * 1000L
    }
}
