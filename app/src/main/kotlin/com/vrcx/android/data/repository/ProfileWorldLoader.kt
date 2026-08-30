package com.vrcx.android.data.repository

import com.vrcx.android.data.api.BulkPaginator
import com.vrcx.android.data.api.FavoriteApi
import com.vrcx.android.data.api.WorldApi
import com.vrcx.android.data.api.model.FavoriteGroup
import com.vrcx.android.data.api.model.World
import com.vrcx.android.data.util.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class FavoriteWorldSection(
    val tag: String,
    val displayName: String,
    val visibility: String,
    val worlds: List<World>,
)

data class FavoriteWorldLoadResult(val sections: List<FavoriteWorldSection>, val warning: String? = null)

/** Loads the world collections displayed on a user's profile. */
@Singleton
internal class ProfileWorldLoader @Inject constructor(
    private val worldApi: WorldApi,
    private val favoriteApi: FavoriteApi,
) {
    suspend fun loadWorlds(userId: String): List<World> = BulkPaginator.fetchAll(
        pageSize = PROFILE_PAGE_SIZE,
    ) { offset, count ->
        worldApi.getWorlds(n = count, offset = offset, user = userId)
    }

    suspend fun loadFavoriteWorlds(userId: String): FavoriteWorldLoadResult {
        val groups = BulkPaginator.fetchAll<FavoriteGroup>(pageSize = PROFILE_PAGE_SIZE) { offset, count ->
            favoriteApi.getFavoriteGroups(
                n = count,
                offset = offset,
                type = "world",
                ownerId = userId,
            )
        }.filter { it.type == "world" }
        if (groups.isEmpty()) return FavoriteWorldLoadResult(emptyList())

        // Stay below OkHttp's per-host request limit so this fan-out cannot
        // starve the profile's other independently loaded tabs.
        val gate = Semaphore(FAVORITE_GROUP_CONCURRENCY)
        val results = supervisorScope {
            groups.map { group ->
                async {
                    runCatchingCancellable {
                        FavoriteWorldSection(
                            tag = group.name,
                            displayName = group.displayName.ifBlank { group.name },
                            visibility = group.visibility,
                            worlds = gate.withPermit {
                                BulkPaginator.fetchAll(pageSize = PROFILE_PAGE_SIZE) { offset, count ->
                                    favoriteApi.getFavoriteWorlds(
                                        n = count,
                                        offset = offset,
                                        tag = group.name,
                                        ownerId = userId,
                                    )
                                }
                            },
                        )
                    }
                }
            }.awaitAll()
        }
        val successfulSections = results.mapNotNull { it.getOrNull() }
        check(successfulSections.isNotEmpty()) { "No favorite world groups could be loaded" }
        val sections = successfulSections.filter { it.worlds.isNotEmpty() }
        val warning = if (results.any { it.isFailure }) {
            "Some favorite world groups could not be loaded"
        } else {
            null
        }
        return FavoriteWorldLoadResult(sections, warning)
    }

    private companion object {
        const val PROFILE_PAGE_SIZE = 100
        const val FAVORITE_GROUP_CONCURRENCY = 4
    }
}
