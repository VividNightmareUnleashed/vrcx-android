package com.vrcx.android.data.api

import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.Path

interface InviteMessageApi {
    @GET("message/{userId}/{messageType}")
    suspend fun getInviteMessages(
        @Path("userId") userId: String,
        @Path("messageType") messageType: String,
    ): JsonElement

    @PUT("message/{userId}/{messageType}/{slot}")
    suspend fun editInviteMessage(
        @Path("userId") userId: String,
        @Path("messageType") messageType: String,
        @Path("slot") slot: Int,
        @Body body: Map<String, String>,
    ): JsonElement
}
