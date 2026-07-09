package com.vrcx.android.data.api

import com.vrcx.android.data.api.model.GalleryImage
import com.vrcx.android.data.api.model.VrcPrint
import kotlinx.serialization.json.JsonElement
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

interface GalleryApi {
    @GET("files")
    suspend fun getFileList(
        @Query("n") n: Int = 100,
        @Query("offset") offset: Int = 0,
        @Query("tag") tag: String? = null,
    ): List<GalleryImage>

    @DELETE("file/{fileId}")
    suspend fun deleteFile(@Path("fileId") fileId: String): JsonElement

    @GET("prints/user/{userId}")
    suspend fun getPrints(
        @Path("userId") userId: String,
        @Query("n") n: Int = 100,
        @Query("offset") offset: Int = 0,
    ): List<VrcPrint>

    @GET("prints/{printId}")
    suspend fun getPrint(@Path("printId") printId: String): VrcPrint

    @DELETE("prints/{printId}")
    suspend fun deletePrint(@Path("printId") printId: String): JsonElement

    @Multipart
    @POST("file/image")
    suspend fun uploadFile(
        @Part("tag") tag: RequestBody,
        @Part file: MultipartBody.Part,
    ): GalleryImage

    @Multipart
    @POST("prints")
    suspend fun uploadPrint(
        @Part image: MultipartBody.Part,
        @Part("note") note: RequestBody? = null,
    ): VrcPrint
}
