package com.vrcx.android.data.repository

import com.vrcx.android.data.api.AvatarApi
import com.vrcx.android.data.api.RequestDeduplicator
import com.vrcx.android.data.api.model.Avatar
import com.vrcx.android.data.db.dao.CacheDao
import com.vrcx.android.data.db.entity.CacheAvatarEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AvatarRepository @Inject constructor(
    private val avatarApi: AvatarApi,
    private val dedup: RequestDeduplicator,
    private val cacheDao: CacheDao,
    private val json: Json,
) {
    private val avatarCache = ConcurrentHashMap<String, Avatar>()

    private val _myAvatars = MutableStateFlow<List<Avatar>>(emptyList())
    val myAvatars: StateFlow<List<Avatar>> = _myAvatars.asStateFlow()

    suspend fun loadMyAvatars() {
        _myAvatars.value = avatarApi.getAvatars(user = "me", releaseStatus = "all", n = 100)
    }

    fun clearRuntimeState() {
        avatarCache.clear()
        _myAvatars.value = emptyList()
    }

    suspend fun selectAvatar(avatarId: String) {
        avatarApi.selectAvatar(avatarId)
    }

    suspend fun getAvatar(avatarId: String): Avatar {
        avatarCache[avatarId]?.let { return it }

        // Check Room cache
        cacheDao.getAvatar(avatarId)?.let { entity ->
            try {
                val avatar = json.decodeFromString(Avatar.serializer(), entity.data)
                avatarCache[avatarId] = avatar
                return avatar
            } catch (_: Exception) {}
        }

        // Fetch from API (deduplicated)
        val avatar = dedup.dedupGet("avatar:$avatarId") { avatarApi.getAvatar(avatarId) }
        avatarCache[avatarId] = avatar
        try {
            cacheDao.insertAvatar(CacheAvatarEntity(
                id = avatarId,
                data = json.encodeToString(Avatar.serializer(), avatar),
                updatedAt = java.time.Instant.now().toString(),
            ))
        } catch (_: Exception) {}
        return avatar
    }
}
