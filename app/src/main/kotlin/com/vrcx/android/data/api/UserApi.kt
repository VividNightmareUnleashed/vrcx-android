package com.vrcx.android.data.api

import com.vrcx.android.data.api.model.CurrentUser
import com.vrcx.android.data.api.model.UserSearchResult
import com.vrcx.android.data.api.model.VrcUser
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface UserApi {
    @GET("users/{userId}")
    suspend fun getUser(@Path("userId") userId: String): VrcUser

    @GET("users")
    suspend fun getUsers(
        @Query("n") n: Int = 10,
        @Query("offset") offset: Int = 0,
        @Query("search") search: String? = null,
        @Query("sort") sort: String? = null,
        @Query("order") order: String? = null,
    ): List<UserSearchResult>

    @PUT("users/{userId}")
    suspend fun saveCurrentUser(
        @Path("userId") userId: String,
        @Body body: Map<String, @JvmSuppressWildcards Any>,
    ): CurrentUser
}
