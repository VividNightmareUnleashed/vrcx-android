package com.vrcx.android.data.api

import com.vrcx.android.data.api.model.AvatarModeration
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface AvatarModerationApi {
    @GET("auth/user/avatarmoderations")
    suspend fun getAvatarModerations(): List<AvatarModeration>

    @POST("auth/user/avatarmoderations")
    suspend fun sendAvatarModeration(@Body body: Map<String, String>): JsonElement

    @DELETE("auth/user/avatarmoderations")
    suspend fun deleteAvatarModeration(
        @Query("targetAvatarId") targetAvatarId: String,
        @Query("avatarModerationType") type: String,
    ): JsonElement
}
