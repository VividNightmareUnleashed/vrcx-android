package com.vrcx.android.data.api

import com.vrcx.android.data.api.model.PlayerModeration
import com.vrcx.android.data.api.model.PlayerModerationRequest
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface PlayerModerationApi {
    @GET("auth/user/playermoderations")
    suspend fun getPlayerModerations(): List<PlayerModeration>

    @POST("auth/user/playermoderations")
    suspend fun sendPlayerModeration(@Body body: PlayerModerationRequest): PlayerModeration

    @DELETE("auth/user/playermoderations/{moderationId}")
    suspend fun deletePlayerModeration(@Path("moderationId") moderationId: String): JsonElement
}
