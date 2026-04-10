package com.vrcx.android.data.repository

import com.vrcx.android.data.api.BulkPaginator
import com.vrcx.android.data.api.FavoriteApi
import com.vrcx.android.data.api.model.Favorite
import com.vrcx.android.data.api.model.FavoriteGroup
import com.vrcx.android.data.api.model.FavoriteLimits
import com.vrcx.android.data.db.dao.FavoriteLocalDao
import com.vrcx.android.data.db.entity.FavoriteFriendEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FavoriteRepository @Inject constructor(
    private val favoriteApi: FavoriteApi,
    private val favoriteLocalDao: FavoriteLocalDao,
) {
    private val favoriteMutex = Mutex()
    private val loadedFavoriteTypes = mutableSetOf<String>()
    private var favoriteGroupsLoaded = false
    private var favoriteLimitsLoaded = false

    private val _favorites = MutableStateFlow<List<Favorite>>(emptyList())
    val favorites: StateFlow<List<Favorite>> = _favorites.asStateFlow()

    private val _favoriteGroups = MutableStateFlow<List<FavoriteGroup>>(emptyList())
    val favoriteGroups: StateFlow<List<FavoriteGroup>> = _favoriteGroups.asStateFlow()

    private val _favoriteLimits = MutableStateFlow<FavoriteLimits?>(null)
    val favoriteLimits: StateFlow<FavoriteLimits?> = _favoriteLimits.asStateFlow()

    suspend fun clearRuntimeState() {
        favoriteMutex.withLock {
            resetRuntimeStateLocked()
        }
    }

    suspend fun loadFavorites(type: String? = null, forceRefresh: Boolean = false) {
        val requestedTypes = if (type == null) DEFAULT_FAVORITE_TYPES else listOf(type)
        val typesToLoad = favoriteMutex.withLock {
            if (forceRefresh) {
                loadedFavoriteTypes.removeAll(requestedTypes.toSet())
            }
            requestedTypes.filterNot { it in loadedFavoriteTypes }
        }
        if (typesToLoad.isEmpty()) return

        val loadedFavorites = typesToLoad.associateWith { favoriteType ->
            BulkPaginator.fetchAll(pageSize = FAVORITES_PAGE_SIZE) { offset, count ->
                favoriteApi.getFavorites(
                    n = count,
                    offset = offset,
                    type = favoriteType,
                )
            }
        }

        favoriteMutex.withLock {
            var merged = _favorites.value
            for ((favoriteType, items) in loadedFavorites) {
                merged = merged.filterNot { it.type == favoriteType } + items
                loadedFavoriteTypes.add(favoriteType)
            }
            _favorites.value = merged
        }
    }

    suspend fun loadFavoriteGroups(forceRefresh: Boolean = false) {
        val shouldLoad = favoriteMutex.withLock {
            if (forceRefresh) {
                favoriteGroupsLoaded = false
            }
            !favoriteGroupsLoaded
        }
        if (!shouldLoad) return

        val groups = BulkPaginator.fetchAll(pageSize = FAVORITE_GROUPS_PAGE_SIZE) { offset, count ->
            favoriteApi.getFavoriteGroups(
                n = count,
                offset = offset,
            )
        }

        favoriteMutex.withLock {
            _favoriteGroups.value = groups
            favoriteGroupsLoaded = true
        }
    }

    suspend fun loadFavoriteLimits(forceRefresh: Boolean = false) {
        val shouldLoad = favoriteMutex.withLock {
            if (forceRefresh) {
                favoriteLimitsLoaded = false
            }
            !favoriteLimitsLoaded
        }
        if (!shouldLoad) return

        val limits = favoriteApi.getFavoriteLimits()
        favoriteMutex.withLock {
            _favoriteLimits.value = limits
            favoriteLimitsLoaded = true
        }
    }

    suspend fun addFavorite(type: String, favoriteId: String, tags: List<String> = emptyList()): Favorite {
        val resolvedTags = if (tags.isNotEmpty()) {
            tags
        } else {
            runCatching { getPreferredFavoriteTags(type) }
                .getOrElse { defaultFavoriteTags(type) }
                .ifEmpty { defaultFavoriteTags(type) }
        }
        val favorite = favoriteApi.addFavorite(
            com.vrcx.android.data.api.model.FavoriteAddRequest(type, favoriteId, resolvedTags)
        )
        _favorites.value = _favorites.value
            .filterNot { it.type == type && it.favoriteId == favoriteId }
            .plus(favorite)
        return favorite
    }

    suspend fun deleteFavorite(favoriteId: String) {
        favoriteApi.deleteFavorite(favoriteId)
        _favorites.value = _favorites.value.filter { it.id != favoriteId }
    }

    suspend fun getPreferredFavoriteTags(type: String): List<String> {
        loadFavorites(type = type)
        loadFavoriteGroups()
        loadFavoriteLimits()

        val limits = _favoriteLimits.value
        val groupLimit = limits?.maxFavoritesPerGroup?.get(type)
        val groupCounts = _favorites.value
            .filter { it.type == type }
            .flatMap { it.tags }
            .groupingBy { it }
            .eachCount()

        val preferredTag = buildFavoriteGroupNames(type, limits).firstOrNull { groupName ->
            groupLimit == null || groupCounts.getOrDefault(groupName, 0) < groupLimit
        } ?: DEFAULT_FAVORITE_TAGS[type]

        return listOfNotNull(preferredTag)
    }

    private fun buildFavoriteGroupNames(type: String, limits: FavoriteLimits?): List<String> {
        val generatedNames = when (type) {
            "friend" -> {
                val max = limits?.maxFavoriteGroups?.get("friend") ?: 1
                (0 until max).map { "group_$it" }
            }
            "world" -> {
                val max = limits?.maxFavoriteGroups?.get("world") ?: 1
                (1..max).map { "worlds$it" }
            }
            "avatar" -> {
                val max = limits?.maxFavoriteGroups?.get("avatar") ?: 1
                (1..max).map { "avatars$it" }
            }
            else -> emptyList()
        }
        val savedNames = _favoriteGroups.value
            .filter { it.type == type }
            .map { it.name }
        return (savedNames + generatedNames).distinct()
    }

    private fun defaultFavoriteTags(type: String): List<String> {
        return listOfNotNull(DEFAULT_FAVORITE_TAGS[type])
    }

    fun getLocalFriends(userId: String) = favoriteLocalDao.getFriends(userId)
    fun getLocalWorlds(userId: String) = favoriteLocalDao.getWorlds(userId)
    fun getLocalAvatars(userId: String) = favoriteLocalDao.getAvatars(userId)

    companion object {
        private val DEFAULT_FAVORITE_TYPES = listOf("friend", "world", "avatar")
        private val DEFAULT_FAVORITE_TAGS = mapOf(
            "friend" to "group_0",
            "world" to "worlds1",
            "avatar" to "avatars1",
        )
        private const val FAVORITES_PAGE_SIZE = 100
        private const val FAVORITE_GROUPS_PAGE_SIZE = 50
    }

    private fun resetRuntimeStateLocked() {
        loadedFavoriteTypes.clear()
        favoriteGroupsLoaded = false
        favoriteLimitsLoaded = false
        _favorites.value = emptyList()
        _favoriteGroups.value = emptyList()
        _favoriteLimits.value = null
    }
}
