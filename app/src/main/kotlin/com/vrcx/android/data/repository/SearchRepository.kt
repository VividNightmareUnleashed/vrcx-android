package com.vrcx.android.data.repository

import com.vrcx.android.data.api.AvatarApi
import com.vrcx.android.data.api.GroupApi
import com.vrcx.android.data.api.UserApi
import com.vrcx.android.data.api.WorldApi
import com.vrcx.android.data.api.model.Avatar
import com.vrcx.android.data.api.model.GroupSearchResult
import com.vrcx.android.data.api.model.UserSearchResult
import com.vrcx.android.data.api.model.World
import com.vrcx.android.di.IoDispatcher
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer

@Singleton
class SearchRepository @Inject constructor(
    private val userApi: UserApi,
    private val worldApi: WorldApi,
    private val avatarApi: AvatarApi,
    private val groupApi: GroupApi,
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val remoteAvatarClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .cookieJar(CookieJar.NO_COOKIES)
            .apply {
                interceptors().clear()
                networkInterceptors().clear()
            }
            .build()
    }

    suspend fun searchUsers(
        query: String,
        n: Int = 10,
        offset: Int = 0,
        searchByBio: Boolean = false,
        sortByLastLogin: Boolean = false,
    ): List<UserSearchResult> = userApi.getUsers(
        n = n,
        offset = offset,
        search = query,
        customFields = if (searchByBio) "bio" else "displayName",
        sort = if (sortByLastLogin) "last_login" else "relevance",
    )

    suspend fun searchWorlds(
        query: String,
        n: Int = 10,
        offset: Int = 0,
        mode: String = "search",
        includeLabs: Boolean = false,
        tag: String? = null,
    ): List<World> {
        val normalizedTag = buildWorldSearchTag(includeLabs = includeLabs, tag = tag)
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

    suspend fun searchAvatars(query: String, n: Int = 10, offset: Int = 0): List<Avatar> =
        avatarApi.getAvatars(n = n, offset = offset, search = query)

    suspend fun searchRemoteAvatars(query: String, providerUrl: String): List<Avatar> {
        val httpUrl = providerUrl.toHttpUrlOrNull()
            ?.newBuilder()
            ?.setQueryParameter("search", query)
            ?.setQueryParameter("n", MAX_REMOTE_AVATARS.toString())
            ?.build()
            ?: invalidProviderUrl()

        val request = Request.Builder()
            .url(httpUrl)
            .header("Referer", "https://vrcx.app")
            .build()

        return withContext(ioDispatcher) {
            remoteAvatarClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    providerFailure(
                        "returned HTTP ${response.code}. Check the provider URL and access requirements.",
                    )
                }
                val responseBody = response.body
                    ?: providerFailure("returned an empty response.")
                val contentLength = responseBody.contentLength()
                if (contentLength > MAX_REMOTE_RESPONSE_BYTES) {
                    providerFailure("response is too large.")
                }
                val source = responseBody.source()
                val buffer = Buffer()
                while (buffer.size <= MAX_REMOTE_RESPONSE_BYTES) {
                    val remaining = MAX_REMOTE_RESPONSE_BYTES + 1L - buffer.size
                    if (source.read(buffer, minOf(READ_BUFFER_BYTES, remaining)) == -1L) break
                }
                val bytes = buffer.readByteArray()
                if (bytes.size > MAX_REMOTE_RESPONSE_BYTES) {
                    providerFailure("response is too large.")
                }
                val body = bytes.toString(Charsets.UTF_8)
                if (body.isBlank()) {
                    providerFailure("returned an empty response.")
                }
                parseRemoteAvatars(body)
            }
        }
    }

    suspend fun searchGroups(query: String, n: Int = 10, offset: Int = 0): List<GroupSearchResult> =
        groupApi.searchGroups(n = n, offset = offset, query = query)

    private fun parseRemoteAvatars(body: String): List<Avatar> {
        val parsed = runCatching { json.parseToJsonElement(body) }
            .getOrElse { providerFailure("returned invalid JSON.") }
        val items = when (parsed) {
            is JsonArray -> parsed

            is JsonObject -> parsed["avatars"] as? JsonArray
                ?: providerFailure("returned an unsupported response format.")

            else -> providerFailure("returned an unsupported response format.")
        }
        val avatarsById = linkedMapOf<String, Avatar>()
        items.take(MAX_REMOTE_AVATARS).forEach { element ->
            val avatar = remoteAvatarFromJson(element) ?: return@forEach
            avatarsById.putIfAbsent(avatar.id, avatar)
        }
        if (items.isNotEmpty() && avatarsById.isEmpty()) {
            providerFailure("returned avatars in an unsupported format.")
        }
        return avatarsById.values.toList()
    }

    private fun invalidProviderUrl(): Nothing =
        throw IllegalArgumentException("Enter a valid remote avatar provider URL.")

    private fun providerFailure(reason: String): Nothing = throw IOException("Remote avatar provider $reason")

    private fun remoteAvatarFromJson(element: JsonElement): Avatar? {
        val jsonObject = element as? JsonObject ?: return null
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
            thumbnailImageUrl = jsonObject.stringValue(
                "thumbnailImageUrl",
                "ThumbnailImageUrl",
                "imageUrl",
                "ImageUrl",
            ),
            updatedAt = jsonObject.stringValue("updated_at", "updatedAt", "UpdatedAt"),
        )
    }

    private fun JsonObject.stringValue(vararg keys: String): String {
        for (key in keys) {
            val value = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            if (!value.isNullOrBlank()) {
                return value
            }
        }
        return ""
    }

    private companion object {
        const val MAX_REMOTE_RESPONSE_BYTES = 5 * 1024 * 1024
        const val MAX_REMOTE_AVATARS = 1_000
        const val READ_BUFFER_BYTES = 8_192L
    }
}

private fun buildWorldSearchTag(includeLabs: Boolean, tag: String?): String? {
    val tags = buildList {
        tag?.trim()?.takeIf(String::isNotEmpty)?.let(::add)
        if (!includeLabs) add("system_approved")
    }
    return tags.distinct().takeIf { it.isNotEmpty() }?.joinToString(",")
}
