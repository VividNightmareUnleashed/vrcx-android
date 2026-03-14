package com.vrcx.android.data.api

import com.vrcx.android.data.api.model.GalleryImage
import kotlinx.serialization.json.JsonElement
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface GalleryApi {
    @GET("files")
    suspend fun getFileList(
        @Query("n") n: Int = 100,
        @Query("offset") offset: Int = 0,
        @Query("tag") tag: String? = null,
    ): List<GalleryImage>

    @DELETE("files/{fileId}")
    suspend fun deleteFile(@Path("fileId") fileId: String): JsonElement

    @GET("prints")
    suspend fun getPrints(
        @Query("n") n: Int = 100,
        @Query("offset") offset: Int = 0,
        @Query("userId") userId: String? = null,
    ): List<JsonElement>

    @GET("prints/{printId}")
    suspend fun getPrint(@Path("printId") printId: String): JsonElement

    @DELETE("prints/{printId}")
    suspend fun deletePrint(@Path("printId") printId: String): JsonElement
}
