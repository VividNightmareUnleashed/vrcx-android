package com.vrcx.android.data.repository

import com.vrcx.android.data.api.AvatarApi
import com.vrcx.android.data.api.GroupApi
import com.vrcx.android.data.api.UserApi
import com.vrcx.android.data.api.WorldApi
import com.vrcx.android.data.api.model.Avatar
import com.vrcx.android.data.api.model.GroupSearchResult
import com.vrcx.android.data.api.model.UserSearchResult
import com.vrcx.android.data.api.model.World
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SearchRepository @Inject constructor(
    private val userApi: UserApi,
    private val worldApi: WorldApi,
    private val avatarApi: AvatarApi,
    private val groupApi: GroupApi,
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) {
    suspend fun searchUsers(
        query: String,
        n: Int = 10,
        offset: Int = 0,
        searchByBio: Boolean = false,
        sortByLastLogin: Boolean = false,
    ): List<UserSearchResult> {
        return userApi.getUsers(
            n = n,
            offset = offset,
            search = query,
            customFields = if (searchByBio) "bio" else "displayName",
            sort = if (sortByLastLogin) "last_login" else "relevance",
        )
    }

    suspend fun searchWorlds(
        query: String,
        n: Int = 10,
        offset: Int = 0,
        mode: String = "search",
        includeLabs: Boolean = false,
        tag: String? = null,
    ): List<World> {
        val normalizedTag = buildWorldTag(includeLabs = includeLabs, tag = tag)
        return when (mode) {
            "active" -> worldApi.getActiveWorlds(n = n, offset = offset, tag = normalizedTag)
            "recent" -> worldApi.getRecentWorlds(n = n, offset = offset, tag = normalizedTag)
            "favorites" -> worldApi.getFavoriteWorlds(n = n, offset = offset, tag = normalizedTag)
            "mine" -> worldApi.getWorlds(
                n = n,
                offset = offset,
                user = "me",
                releaseStatus = "all",
                tag = normalizedTag,
            )
            else -> worldApi.getWorlds(
                n = n,
                offset = offset,
                search = query,
                sort = "relevance",
                tag = normalizedTag,
            )
        }
    }

    suspend fun searchAvatars(query: String, n: Int = 10, offset: Int = 0): List<Avatar> {
        return avatarApi.getAvatars(n = n, offset = offset, search = query)
    }

    suspend fun searchRemoteAvatars(query: String, providerUrl: String): List<Avatar> {
        val httpUrl = providerUrl.toHttpUrlOrNull()
            ?.newBuilder()
            ?.setQueryParameter("search", query)
            ?.setQueryParameter("n", "5000")
            ?.build()
            ?: return emptyList()

        val request = Request.Builder()
            .url(httpUrl)
            .header("Referer", "https://vrcx.app")
            .build()

        return okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return emptyList()
            parseRemoteAvatars(body)
        }
    }

    suspend fun searchGroups(query: String, n: Int = 10, offset: Int = 0): List<GroupSearchResult> {
        return groupApi.searchGroups(n = n, offset = offset, query = query)
    }

    private fun buildWorldTag(includeLabs: Boolean, tag: String?): String? {
        val tags = buildList {
            val trimmedTag = tag?.trim().orEmpty()
            if (trimmedTag.isNotEmpty()) {
                add(trimmedTag)
            }
            if (!includeLabs) {
                add("system_approved")
            }
        }
        return tags.distinct().takeIf { it.isNotEmpty() }?.joinToString(",")
    }

    private fun parseRemoteAvatars(body: String): List<Avatar> {
        val parsed = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
        val items = when (parsed) {
            is JsonArray -> parsed
            is JsonObject -> parsed["avatars"]?.jsonArray ?: return emptyList()
            else -> return emptyList()
        }
        return items.mapNotNull(::remoteAvatarFromJson)
    }

    private fun remoteAvatarFromJson(element: JsonElement): Avatar? {
        val jsonObject = runCatching { element.jsonObject }.getOrNull() ?: return null
        val avatarId = jsonObject.stringValue("id", "Id")
        if (avatarId.isBlank()) return null
        return Avatar(
            authorId = jsonObject.stringValue("authorId", "AuthorId"),
            authorName = jsonObject.stringValue("authorName", "AuthorName"),
            createdAt = jsonObject.stringValue("created_at", "createdAt", "CreatedAt"),
            description = jsonObject.stringValue("description", "Description"),
            id = avatarId,
            imageUrl = jsonObject.stringValue("imageUrl", "ImageUrl"),
            name = jsonObject.stringValue("name", "Name"),
            releaseStatus = jsonObject.stringValue("releaseStatus", "ReleaseStatus").ifBlank { "public" },
            thumbnailImageUrl = jsonObject.stringValue("thumbnailImageUrl", "ThumbnailImageUrl", "imageUrl", "ImageUrl"),
            updatedAt = jsonObject.stringValue("updated_at", "updatedAt", "UpdatedAt"),
        )
    }

    private fun JsonObject.stringValue(vararg keys: String): String {
        for (key in keys) {
            val value = this[key]?.jsonPrimitive?.content
            if (!value.isNullOrBlank()) {
                return value
            }
        }
        return ""
    }
}
