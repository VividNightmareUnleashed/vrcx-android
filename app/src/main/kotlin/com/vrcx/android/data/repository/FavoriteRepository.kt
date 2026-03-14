package com.vrcx.android.data.repository

import com.vrcx.android.data.api.FavoriteApi
import com.vrcx.android.data.api.model.Favorite
import com.vrcx.android.data.api.model.FavoriteGroup
import com.vrcx.android.data.db.dao.FavoriteLocalDao
import com.vrcx.android.data.db.entity.FavoriteFriendEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FavoriteRepository @Inject constructor(
    private val favoriteApi: FavoriteApi,
    private val favoriteLocalDao: FavoriteLocalDao,
) {
    private val _favorites = MutableStateFlow<List<Favorite>>(emptyList())
    val favorites: StateFlow<List<Favorite>> = _favorites.asStateFlow()

    private val _favoriteGroups = MutableStateFlow<List<FavoriteGroup>>(emptyList())
    val favoriteGroups: StateFlow<List<FavoriteGroup>> = _favoriteGroups.asStateFlow()

    suspend fun loadFavorites(type: String? = null) {
        _favorites.value = favoriteApi.getFavorites(type = type)
    }

    suspend fun loadFavoriteGroups() {
        _favoriteGroups.value = favoriteApi.getFavoriteGroups()
    }

    suspend fun addFavorite(type: String, favoriteId: String, tags: List<String>): Favorite {
        return favoriteApi.addFavorite(
            com.vrcx.android.data.api.model.FavoriteAddRequest(type, favoriteId, tags)
        )
    }

    suspend fun deleteFavorite(favoriteId: String) {
        favoriteApi.deleteFavorite(favoriteId)
        _favorites.value = _favorites.value.filter { it.id != favoriteId }
    }

    fun getLocalFriends(userId: String) = favoriteLocalDao.getFriends(userId)
    fun getLocalWorlds(userId: String) = favoriteLocalDao.getWorlds(userId)
    fun getLocalAvatars(userId: String) = favoriteLocalDao.getAvatars(userId)
}
