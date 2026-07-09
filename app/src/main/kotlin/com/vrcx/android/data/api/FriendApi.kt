package com.vrcx.android.data.api

import com.vrcx.android.data.api.model.VrcUser
import kotlinx.serialization.json.JsonElement
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface FriendApi {
    @GET("auth/user/friends")
    suspend fun getFriends(
        @Query("n") n: Int = 100,
        @Query("offset") offset: Int = 0,
        @Query("offline") offline: Boolean = false,
    ): List<VrcUser>

    @POST("user/{userId}/friendRequest")
    suspend fun sendFriendRequest(@Path("userId") userId: String): JsonElement

    @DELETE("user/{userId}/friendRequest")
    suspend fun cancelFriendRequest(@Path("userId") userId: String): JsonElement

    @DELETE("auth/user/friends/{userId}")
    suspend fun deleteFriend(@Path("userId") userId: String): JsonElement

    @GET("user/{userId}/friendStatus")
    suspend fun getFriendStatus(@Path("userId") userId: String): JsonElement
}
