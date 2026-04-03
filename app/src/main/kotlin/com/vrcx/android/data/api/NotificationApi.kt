package com.vrcx.android.data.api

import com.vrcx.android.data.api.model.NotificationV2
import com.vrcx.android.data.api.model.NotificationResponse
import com.vrcx.android.data.api.model.VrcNotification
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface NotificationApi {
    @GET("auth/user/notifications")
    suspend fun getNotifications(
        @Query("n") n: Int = 100,
        @Query("offset") offset: Int = 0,
        @Query("type") type: String? = null,
        @Query("sent") sent: Boolean = false,
    ): List<VrcNotification>

    @GET("notifications")
    suspend fun getNotificationsV2(
        @Query("n") n: Int = 100,
        @Query("offset") offset: Int = 0,
        @Query("type") type: String? = null,
    ): List<NotificationV2>

    @PUT("auth/user/notifications/{notificationId}/see")
    suspend fun seeNotification(@Path("notificationId") notificationId: String): JsonElement

    @PUT("auth/user/notifications/{notificationId}/hide")
    suspend fun hideNotification(@Path("notificationId") notificationId: String): JsonElement

    @POST("notifications/{notificationId}/see")
    suspend fun seeNotificationV2(@Path("notificationId") notificationId: String): JsonElement

    @DELETE("notifications/{notificationId}")
    suspend fun hideNotificationV2(@Path("notificationId") notificationId: String): JsonElement

    @PUT("auth/user/notifications/{notificationId}/accept")
    suspend fun acceptFriendRequest(@Path("notificationId") notificationId: String): JsonElement

    @POST("notifications/{notificationId}/respond")
    suspend fun sendNotificationResponse(
        @Path("notificationId") notificationId: String,
        @Body body: NotificationResponse,
    ): JsonElement

    @POST("invite/{userId}")
    suspend fun sendInvite(
        @Path("userId") userId: String,
        @Body body: Map<String, @JvmSuppressWildcards Any>,
    ): JsonElement

    @POST("requestInvite/{userId}")
    suspend fun sendRequestInvite(
        @Path("userId") userId: String,
        @Body body: Map<String, @JvmSuppressWildcards Any> = emptyMap(),
    ): JsonElement

    @POST("invite/{notificationId}/response")
    suspend fun sendInviteResponse(
        @Path("notificationId") notificationId: String,
        @Body body: Map<String, @JvmSuppressWildcards Any>,
    ): JsonElement
}
