package com.vrcx.android.data.api

import com.vrcx.android.data.api.model.CurrentUser
import com.vrcx.android.data.api.model.UserSearchResult
import com.vrcx.android.data.api.model.VrcUser
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface UserApi {
    @GET("users/{userId}")
    suspend fun getUser(@Path("userId") userId: String): VrcUser

    @GET("users")
    suspend fun getUsers(
        @Query("n") n: Int = 10,
        @Query("offset") offset: Int = 0,
        @Query("search") search: String? = null,
        @Query("customFields") customFields: String? = null,
        @Query("sort") sort: String? = null,
        @Query("order") order: String? = null,
    ): List<UserSearchResult>

    @PUT("users/{userId}")
    suspend fun saveCurrentUser(
        @Path("userId") userId: String,
        @Body body: Map<String, @JvmSuppressWildcards Any>,
    ): CurrentUser

    @GET("users/{userId}/mutuals")
    suspend fun getMutualCounts(@Path("userId") userId: String): JsonElement

    @GET("users/{userId}/mutuals/friends")
    suspend fun getMutualFriends(
        @Path("userId") userId: String,
        @Query("n") n: Int = 100,
        @Query("offset") offset: Int = 0,
    ): List<VrcUser>

    @GET("users/{userId}/mutuals/groups")
    suspend fun getMutualGroups(
        @Path("userId") userId: String,
        @Query("n") n: Int = 100,
        @Query("offset") offset: Int = 0,
    ): JsonElement

    @GET("userNotes")
    suspend fun getUserNotes(
        @Query("n") n: Int = 100,
        @Query("offset") offset: Int = 0,
    ): JsonElement

    @POST("userNotes")
    suspend fun saveUserNote(@Body body: Map<String, String>): JsonElement

    @POST("users/{userId}/boop")
    suspend fun sendBoop(
        @Path("userId") userId: String,
        @Body body: Map<String, String> = emptyMap(),
    ): JsonElement

    @POST("feedback/{userId}/user")
    suspend fun reportUser(
        @Path("userId") userId: String,
        @Body body: Map<String, String>,
    ): JsonElement
}
