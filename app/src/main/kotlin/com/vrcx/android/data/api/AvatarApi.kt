package com.vrcx.android.data.api

import com.vrcx.android.data.api.model.Avatar
import com.vrcx.android.data.api.model.CurrentUser
import kotlinx.serialization.json.JsonElement
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface AvatarApi {
    @GET("avatars/{avatarId}")
    suspend fun getAvatar(@Path("avatarId") avatarId: String): Avatar

    @GET("avatars")
    suspend fun getAvatars(
        @Query("n") n: Int = 10,
        @Query("offset") offset: Int = 0,
        @Query("search") search: String? = null,
        @Query("sort") sort: String? = null,
        @Query("order") order: String? = null,
        @Query("user") user: String? = null,
        @Query("releaseStatus") releaseStatus: String? = null,
    ): List<Avatar>

    @PUT("avatars/{avatarId}/select")
    suspend fun selectAvatar(@Path("avatarId") avatarId: String): CurrentUser

    @PUT("avatars/{avatarId}/selectFallback")
    suspend fun selectFallbackAvatar(@Path("avatarId") avatarId: String): CurrentUser

    @DELETE("avatars/{avatarId}")
    suspend fun deleteAvatar(@Path("avatarId") avatarId: String): JsonElement
}
