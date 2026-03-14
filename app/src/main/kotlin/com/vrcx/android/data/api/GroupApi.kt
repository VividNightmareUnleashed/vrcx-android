package com.vrcx.android.data.api

import com.vrcx.android.data.api.model.Group
import com.vrcx.android.data.api.model.GroupInstance
import com.vrcx.android.data.api.model.GroupMember
import com.vrcx.android.data.api.model.GroupPost
import com.vrcx.android.data.api.model.GroupSearchResult
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface GroupApi {
    @GET("groups/{groupId}")
    suspend fun getGroup(
        @Path("groupId") groupId: String,
        @Query("includeRoles") includeRoles: Boolean = true,
    ): Group

    @GET("users/{userId}/groups")
    suspend fun getUserGroups(
        @Path("userId") userId: String,
        @Query("n") n: Int = 100,
        @Query("offset") offset: Int = 0,
    ): List<Group>

    @GET("groups/{groupId}/members")
    suspend fun getGroupMembers(
        @Path("groupId") groupId: String,
        @Query("n") n: Int = 100,
        @Query("offset") offset: Int = 0,
        @Query("sort") sort: String? = null,
    ): List<GroupMember>

    @GET("groups/{groupId}/instances")
    suspend fun getGroupInstances(
        @Path("groupId") groupId: String,
    ): List<GroupInstance>

    @GET("groups/{groupId}/posts")
    suspend fun getGroupPosts(
        @Path("groupId") groupId: String,
        @Query("n") n: Int = 10,
        @Query("offset") offset: Int = 0,
        @Query("publicOnly") publicOnly: Boolean = false,
    ): List<GroupPost>

    @POST("groups/{groupId}/join")
    suspend fun joinGroup(@Path("groupId") groupId: String): JsonElement

    @POST("groups/{groupId}/leave")
    suspend fun leaveGroup(@Path("groupId") groupId: String): JsonElement

    @PUT("groups/{groupId}/members/{userId}")
    suspend fun updateGroupMember(
        @Path("groupId") groupId: String,
        @Path("userId") userId: String,
        @Body body: Map<String, @JvmSuppressWildcards Any>,
    ): JsonElement

    @DELETE("groups/{groupId}/members/{userId}")
    suspend fun kickGroupMember(
        @Path("groupId") groupId: String,
        @Path("userId") userId: String,
    ): JsonElement

    @POST("groups/{groupId}/bans")
    suspend fun banGroupMember(
        @Path("groupId") groupId: String,
        @Body body: Map<String, String>,
    ): JsonElement

    @DELETE("groups/{groupId}/bans/{userId}")
    suspend fun unbanGroupMember(
        @Path("groupId") groupId: String,
        @Path("userId") userId: String,
    ): JsonElement

    @POST("groups/{groupId}/invites")
    suspend fun inviteToGroup(
        @Path("groupId") groupId: String,
        @Body body: Map<String, String>,
    ): JsonElement

    @GET("groups")
    suspend fun searchGroups(
        @Query("n") n: Int = 10,
        @Query("offset") offset: Int = 0,
        @Query("query") query: String? = null,
    ): List<GroupSearchResult>
}
