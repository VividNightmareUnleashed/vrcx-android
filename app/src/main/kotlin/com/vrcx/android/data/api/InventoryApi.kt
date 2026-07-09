package com.vrcx.android.data.api

import com.vrcx.android.data.api.model.InventoryItem
import com.vrcx.android.data.api.model.InventoryTemplate
import kotlinx.serialization.json.JsonElement
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface InventoryApi {
    @GET("inventory")
    suspend fun getInventoryItems(
        @Query("n") n: Int = 100,
        @Query("offset") offset: Int = 0,
    ): List<InventoryItem>

    @GET("inventory/{itemId}")
    suspend fun getInventoryItem(@Path("itemId") itemId: String): InventoryItem

    @GET("inventory/templates")
    suspend fun getInventoryTemplates(
        @Query("n") n: Int = 100,
        @Query("offset") offset: Int = 0,
    ): List<InventoryTemplate>

    @POST("inventory/{itemId}/consume")
    suspend fun consumeInventoryBundle(@Path("itemId") itemId: String): JsonElement
}
