package com.vrcx.android.data.repository

import com.vrcx.android.data.api.InstanceApi
import com.vrcx.android.data.api.RequestDeduplicator
import com.vrcx.android.data.api.WorldApi
import com.vrcx.android.data.api.model.Instance
import com.vrcx.android.data.api.model.World
import com.vrcx.android.data.db.dao.CacheDao
import com.vrcx.android.data.db.entity.CacheWorldEntity
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    private val dedup: RequestDeduplicator,
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

        // Fetch from API (deduplicated)
        val world = dedup.dedupGet("world:$worldId") { worldApi.getWorld(worldId) }
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

    /**
     * Fetch instance details for the top [MAX_INSTANCE_DETAILS] instance IDs
     * concurrently. Popular worlds advertise dozens or hundreds of active
     * instances — doing those requests sequentially would block the World
     * Detail load on every round trip, drain the rate limiter, and make the
     * screen feel broken. The cap keeps the UI cost bounded; parallel fan-out
     * (via `coroutineScope` + `async`) keeps the wall clock close to a single
     * request even when the cap is reached. Individual failures are still
     * swallowed so one bad instance can't fail the whole screen.
     */
    suspend fun getInstances(worldId: String, instanceIds: List<String>): List<Instance> {
        val capped = instanceIds.take(MAX_INSTANCE_DETAILS)
        if (capped.isEmpty()) return emptyList()
        return coroutineScope {
            capped.map { instanceId ->
                async {
                    try {
                        instanceApi.getInstance(worldId, instanceId)
                    } catch (_: Exception) {
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    /**
     * Sends an invite for the given instance to the current user, mirroring the
     * desktop "Invite Yourself" affordance. Useful on Android because the app
     * cannot launch VRChat directly — accepting the resulting in-app invite is
     * how the user joins from a headset session.
     */
    suspend fun selfInvite(worldId: String, instanceId: String) {
        instanceApi.selfInvite(worldId = worldId, instanceId = instanceId)
    }

    private companion object {
        /** Matches desktop VRCX's active-instance list density. */
        const val MAX_INSTANCE_DETAILS = 20
    }
}
