package com.vrcx.android.data.repository

import com.vrcx.android.data.api.AvatarApi
import com.vrcx.android.data.api.BulkPaginator
import com.vrcx.android.data.api.FavoriteApi
import com.vrcx.android.data.api.WorldApi
import com.vrcx.android.data.api.model.Avatar
import com.vrcx.android.data.api.model.Favorite
import com.vrcx.android.data.api.model.FavoriteGroup
import com.vrcx.android.data.api.model.FavoriteLimits
import com.vrcx.android.data.api.model.World
import com.vrcx.android.data.util.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class FavoriteRepository @Inject constructor(
    private val favoriteApi: FavoriteApi,
    private val worldApi: WorldApi,
    private val avatarApi: AvatarApi,
    accountScope: AccountScope,
) : AccountScoped {
    private val account = accountScope.bindTo(this)
    private val loadCache = FavoriteLoadCache()

    private val _favorites = MutableStateFlow<List<Favorite>>(emptyList())
    val favorites: StateFlow<List<Favorite>> = _favorites.asStateFlow()

    private val _favoriteGroups = MutableStateFlow<List<FavoriteGroup>>(emptyList())
    val favoriteGroups: StateFlow<List<FavoriteGroup>> = _favoriteGroups.asStateFlow()

    @Volatile
    private var favoriteLimits: FavoriteLimits? = null

    private val _favoriteWorlds = MutableStateFlow<List<World>>(emptyList())
    val favoriteWorlds: StateFlow<List<World>> = _favoriteWorlds.asStateFlow()

    private val _favoriteAvatars = MutableStateFlow<List<Avatar>>(emptyList())
    val favoriteAvatars: StateFlow<List<Avatar>> = _favoriteAvatars.asStateFlow()

    /**
     * Hydrates the world favorites list in one shot via /worlds/favorites instead
     * of resolving each Favorite by hitting /worlds/{id} N times. The bulk endpoint
     * is paginated server-side; iterate until a short page comes back.
     */
    suspend fun loadFavoriteWorldsBulk(forceRefresh: Boolean = false) = loadCache.loadOnce(
        account = account,
        key = KEY_BULK_WORLDS,
        forceRefresh = forceRefresh,
        fetch = {
            BulkPaginator.fetchAll(pageSize = FAVORITES_PAGE_SIZE) { offset, count ->
                favoriteApi.getFavoriteWorlds(n = count, offset = offset)
            }
        },
        publish = { _favoriteWorlds.value = it },
    )

    suspend fun loadFavoriteAvatarsBulk(forceRefresh: Boolean = false) = loadCache.loadOnce(
        account = account,
        key = KEY_BULK_AVATARS,
        forceRefresh = forceRefresh,
        fetch = {
            BulkPaginator.fetchAll(pageSize = FAVORITES_PAGE_SIZE) { offset, count ->
                favoriteApi.getFavoriteAvatars(n = count, offset = offset)
            }
        },
        publish = { _favoriteAvatars.value = it },
    )

    override fun clearRuntimeState() {
        loadCache.clear()
        _favorites.value = emptyList()
        _favoriteGroups.value = emptyList()
        favoriteLimits = null
        _favoriteWorlds.value = emptyList()
        _favoriteAvatars.value = emptyList()
    }

    /** Favorites are loaded per type and merged into one list rather than replacing it. */
    suspend fun loadFavorites(type: String, forceRefresh: Boolean = false) = loadCache.loadOnce(
        account = account,
        key = "favorites:$type",
        forceRefresh = forceRefresh,
        fetch = {
            BulkPaginator.fetchAll(pageSize = FAVORITES_PAGE_SIZE) { offset, count ->
                favoriteApi.getFavorites(
                    n = count,
                    offset = offset,
                    type = type,
                )
            }
        },
        publish = { items -> _favorites.value = _favorites.value.filterNot { it.type == type } + items },
    )

    suspend fun loadFavoriteGroups(forceRefresh: Boolean = false) = loadCache.loadOnce(
        account = account,
        key = KEY_GROUPS,
        forceRefresh = forceRefresh,
        fetch = {
            BulkPaginator.fetchAll(pageSize = FAVORITE_GROUPS_PAGE_SIZE) { offset, count ->
                favoriteApi.getFavoriteGroups(
                    n = count,
                    offset = offset,
                )
            }
        },
        publish = { _favoriteGroups.value = it },
    )

    suspend fun loadFavoriteLimits(forceRefresh: Boolean = false) = loadCache.loadOnce(
        account = account,
        key = KEY_LIMITS,
        forceRefresh = forceRefresh,
        fetch = { favoriteApi.getFavoriteLimits() },
        publish = { favoriteLimits = it },
    )

    suspend fun addFavorite(type: String, favoriteId: String, tags: List<String> = emptyList()): Favorite {
        val token = account.current()
        val resolvedTags = if (tags.isNotEmpty()) {
            tags
        } else {
            runCatchingCancellable {
                getPreferredFavoriteTags(type).ifEmpty { FavoriteGroupSelector.defaultTags(type) }
            }.getOrElse { FavoriteGroupSelector.defaultTags(type) }
        }
        account.ensureCurrent(token)
        val favorite = favoriteApi.addFavorite(
            com.vrcx.android.data.api.model.FavoriteAddRequest(type, favoriteId, resolvedTags),
        )
        account.publishOrAbort(token) {
            _favorites.value = _favorites.value
                .filterNot { it.type == type && it.favoriteId == favoriteId }
                .plus(favorite)
        }
        // The bulk caches (_favoriteWorlds, _favoriteAvatars) are populated
        // via /worlds/favorites and /avatars/favorites, which are called only
        // during FavoritesViewModel init. Invalidating the "loaded" flag alone
        // would leave the new entry showing as a raw ID for any screen that's
        // already observing the flow. Fetch the single entity and patch the
        // bulk cache in place so the UI updates immediately; if that fetch
        // fails, fall back to invalidating the flag so the next screen entry
        // gets a chance to catch up via the bulk endpoint.
        runCatchingCancellable {
            when (type) {
                "world" -> {
                    val world = worldApi.getWorld(favoriteId)
                    account.publishOrAbort(token) {
                        _favoriteWorlds.value = _favoriteWorlds.value
                            .filterNot { it.id == world.id } + world
                    }
                }

                "avatar" -> {
                    val avatar = avatarApi.getAvatar(favoriteId)
                    account.publishOrAbort(token) {
                        _favoriteAvatars.value = _favoriteAvatars.value
                            .filterNot { it.id == avatar.id } + avatar
                    }
                }
            }
        }.onFailure {
            account.publishOrAbort(token) {
                when (type) {
                    "world" -> loadCache.invalidate(KEY_BULK_WORLDS)
                    "avatar" -> loadCache.invalidate(KEY_BULK_AVATARS)
                }
            }
        }
        return favorite
    }

    suspend fun deleteFavorite(favoriteId: String) {
        val token = account.current()
        account.ensureCurrent(token)
        val existing = _favorites.value.firstOrNull { it.id == favoriteId }
        favoriteApi.deleteFavorite(favoriteId)
        account.publishOrAbort(token) {
            _favorites.value = _favorites.value.filter { it.id != favoriteId }
            // Drop the underlying world/avatar from the bulk cache immediately
            // so observers never retain an item that was successfully deleted.
            if (existing != null) {
                when (existing.type) {
                    "world", "vrcPlusWorld" ->
                        _favoriteWorlds.value =
                            _favoriteWorlds.value.filterNot { it.id == existing.favoriteId }

                    "avatar" ->
                        _favoriteAvatars.value =
                            _favoriteAvatars.value.filterNot { it.id == existing.favoriteId }
                }
            }
        }
    }

    suspend fun getPreferredFavoriteTags(type: String): List<String> {
        loadFavorites(type = type)
        loadFavoriteGroups()
        loadFavoriteLimits()

        return FavoriteGroupSelector.preferredTags(
            type = type,
            limits = favoriteLimits,
            favorites = _favorites.value,
            groups = _favoriteGroups.value,
        )
    }

    companion object {
        private const val KEY_BULK_WORLDS = "bulk:worlds"
        private const val KEY_BULK_AVATARS = "bulk:avatars"
        private const val KEY_GROUPS = "groups"
        private const val KEY_LIMITS = "limits"
        private const val FAVORITES_PAGE_SIZE = 100
        private const val FAVORITE_GROUPS_PAGE_SIZE = 50
    }
}
