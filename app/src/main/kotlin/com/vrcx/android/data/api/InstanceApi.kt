package com.vrcx.android.data.api

import com.vrcx.android.data.api.model.Instance
import kotlinx.serialization.json.JsonElement
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface InstanceApi {
    @GET("instances/{worldId}:{instanceId}")
    suspend fun getInstance(
        @Path("worldId") worldId: String,
        @Path("instanceId") instanceId: String,
    ): Instance

    @POST("invite/myself/to/{worldId}:{instanceId}")
    suspend fun selfInvite(
        @Path("worldId") worldId: String,
        @Path("instanceId") instanceId: String,
    ): JsonElement
}
