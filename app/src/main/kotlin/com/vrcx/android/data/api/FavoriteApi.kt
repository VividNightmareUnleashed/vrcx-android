package com.vrcx.android.data.api

import com.vrcx.android.data.api.model.Avatar
import com.vrcx.android.data.api.model.Favorite
import com.vrcx.android.data.api.model.FavoriteAddRequest
import com.vrcx.android.data.api.model.FavoriteGroup
import com.vrcx.android.data.api.model.FavoriteGroupUpdateRequest
import com.vrcx.android.data.api.model.FavoriteLimits
import com.vrcx.android.data.api.model.VrcUser
import com.vrcx.android.data.api.model.World
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface FavoriteApi {
    @GET("favorite/limits")
    suspend fun getFavoriteLimits(): FavoriteLimits

    @GET("favorites")
    suspend fun getFavorites(
        @Query("n") n: Int = 100,
        @Query("offset") offset: Int = 0,
        @Query("type") type: String? = null,
        @Query("tag") tag: String? = null,
    ): List<Favorite>

    @POST("favorites")
    suspend fun addFavorite(@Body body: FavoriteAddRequest): Favorite

    @DELETE("favorites/{favoriteId}")
    suspend fun deleteFavorite(@Path("favoriteId") favoriteId: String): JsonElement

    @GET("favorite/groups")
    suspend fun getFavoriteGroups(
        @Query("n") n: Int = 100,
        @Query("offset") offset: Int = 0,
        @Query("type") type: String? = null,
        @Query("ownerId") ownerId: String? = null,
    ): List<FavoriteGroup>

    @PUT("favorite/group/{type}/{groupName}/{userId}")
    suspend fun saveFavoriteGroup(
        @Path("type") type: String,
        @Path("groupName") groupName: String,
        @Path("userId") userId: String,
        @Body body: FavoriteGroupUpdateRequest,
    ): JsonElement

    @DELETE("favorite/group/{type}/{groupName}/{userId}")
    suspend fun clearFavoriteGroup(
        @Path("type") type: String,
        @Path("groupName") groupName: String,
        @Path("userId") userId: String,
    ): JsonElement

    @GET("worlds/favorites")
    suspend fun getFavoriteWorlds(
        @Query("n") n: Int = 100,
        @Query("offset") offset: Int = 0,
        @Query("tag") tag: String? = null,
        @Query("ownerId") ownerId: String? = null,
        @Query("userId") userId: String? = null,
    ): List<World>

    @GET("avatars/favorites")
    suspend fun getFavoriteAvatars(
        @Query("n") n: Int = 100,
        @Query("offset") offset: Int = 0,
        @Query("tag") tag: String? = null,
    ): List<Avatar>
}
