package com.vrcx.android.data.repository

import com.vrcx.android.data.api.InstanceApi
import com.vrcx.android.data.api.WorldApi
import com.vrcx.android.data.api.model.Instance
import com.vrcx.android.data.api.model.World
import com.vrcx.android.data.db.dao.CacheDao
import com.vrcx.android.data.db.entity.CacheWorldEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorldRepository @Inject constructor(
    private val worldApi: WorldApi,
    private val instanceApi: InstanceApi,
    private val cacheDao: CacheDao,
    private val json: Json,
) {
    private val worldCache = ConcurrentHashMap<String, World>()

    suspend fun getWorld(worldId: String): World {
        worldCache[worldId]?.let { return it }

        // Check Room cache
        cacheDao.getWorld(worldId)?.let { entity ->
            try {
                val world = json.decodeFromString(World.serializer(), entity.data)
                worldCache[worldId] = world
                return world
            } catch (_: Exception) {}
        }

        // Fetch from API
        val world = worldApi.getWorld(worldId)
        worldCache[worldId] = world
        try {
            cacheDao.insertWorld(CacheWorldEntity(
                id = worldId,
                data = json.encodeToString(World.serializer(), world),
                updatedAt = java.time.Instant.now().toString(),
            ))
        } catch (_: Exception) {}
        return world
    }

    fun getCachedWorld(worldId: String): World? = worldCache[worldId]

    /** Parse instance IDs from the World.instances field (List<[instanceId, nUsers]>). */
    fun parseInstanceIds(world: World): List<String> {
        return world.instances.mapNotNull { inner ->
            inner.firstOrNull()?.jsonPrimitive?.content
        }
    }

    suspend fun getInstances(worldId: String, instanceIds: List<String>): List<Instance> {
        return instanceIds.take(10).mapNotNull { instanceId ->
            try {
                instanceApi.getInstance(worldId, instanceId)
            } catch (_: Exception) { null }
        }
    }
}
