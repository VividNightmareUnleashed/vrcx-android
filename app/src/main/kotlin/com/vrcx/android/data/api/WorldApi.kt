package com.vrcx.android.data.api

import com.vrcx.android.data.api.model.World
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface WorldApi {
    @GET("worlds/{worldId}")
    suspend fun getWorld(@Path("worldId") worldId: String): World

    @GET("worlds")
    suspend fun getWorlds(
        @Query("n") n: Int = 10,
        @Query("offset") offset: Int = 0,
        @Query("search") search: String? = null,
        @Query("sort") sort: String? = null,
        @Query("order") order: String? = null,
        @Query("user") user: String? = null,
        @Query("releaseStatus") releaseStatus: String? = null,
    ): List<World>

    @GET("worlds/active")
    suspend fun getActiveWorlds(
        @Query("n") n: Int = 10,
        @Query("offset") offset: Int = 0,
    ): List<World>

    @GET("worlds/recent")
    suspend fun getRecentWorlds(
        @Query("n") n: Int = 10,
        @Query("offset") offset: Int = 0,
    ): List<World>

    @GET("worlds/favorites")
    suspend fun getFavoriteWorlds(
        @Query("n") n: Int = 10,
        @Query("offset") offset: Int = 0,
    ): List<World>
}
