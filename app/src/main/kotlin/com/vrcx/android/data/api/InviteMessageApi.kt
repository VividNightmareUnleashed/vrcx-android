package com.vrcx.android.data.api

import kotlinx.serialization.json.JsonElement
import retrofit2.http.GET
import retrofit2.http.Path

interface InviteMessageApi {
    @GET("message/{userId}/{messageType}")
    suspend fun getInviteMessages(
        @Path("userId") userId: String,
        @Path("messageType") messageType: String,
    ): JsonElement

}
