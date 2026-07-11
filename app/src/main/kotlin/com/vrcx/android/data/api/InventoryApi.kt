package com.vrcx.android.data.api

import com.vrcx.android.data.api.model.InventoryItem
import com.vrcx.android.data.api.model.InventoryResponse
import com.vrcx.android.data.api.model.InventoryTemplate
import kotlinx.serialization.json.JsonElement
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface InventoryApi {
    @GET("inventory")
    suspend fun getInventoryItems(
        @Query("n") n: Int = 100,
        @Query("offset") offset: Int = 0,
        @Query("order") order: String = "newest",
    ): InventoryResponse

    @GET("inventory/{itemId}")
    suspend fun getInventoryItem(@Path("itemId") itemId: String): InventoryItem

    @GET("inventory/template/{templateId}")
    suspend fun getInventoryTemplate(@Path("templateId") templateId: String): InventoryTemplate

    @PUT("inventory/{itemId}/consume")
    suspend fun consumeInventoryBundle(@Path("itemId") itemId: String): JsonElement
}
