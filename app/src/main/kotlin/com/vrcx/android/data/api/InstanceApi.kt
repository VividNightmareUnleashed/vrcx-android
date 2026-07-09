package com.vrcx.android.data.api

import com.vrcx.android.data.api.model.Instance
import com.vrcx.android.data.api.model.InstanceShortName
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
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

    @GET("instances/{shortName}")
    suspend fun getInstanceByShortName(@Path("shortName") shortName: String): Instance

    @POST("instances")
    suspend fun createInstance(@Body body: Map<String, @JvmSuppressWildcards Any>): Instance

    @GET("instances/{worldId}:{instanceId}/shortName")
    suspend fun getInstanceShortName(
        @Path("worldId") worldId: String,
        @Path("instanceId") instanceId: String,
    ): InstanceShortName

    @POST("invite/myself/to/{worldId}:{instanceId}")
    suspend fun selfInvite(
        @Path("worldId") worldId: String,
        @Path("instanceId") instanceId: String,
    ): JsonElement
}
