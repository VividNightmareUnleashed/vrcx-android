package com.vrcx.android.data.api.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class World(
    val authorId: String = "",
    val authorName: String = "",
    val capacity: Int = 0,
    val recommendedCapacity: Int = 0,
    @SerialName("created_at") val createdAt: String = "",
    val description: String = "",
    val favorites: Int = 0,
    val featured: Boolean = false,
    val heat: Int = 0,
    val id: String = "",
    val imageUrl: String = "",
    val instances: List<List<kotlinx.serialization.json.JsonElement>> = emptyList(),
    val labsPublicationDate: String = "",
    val name: String = "",
    val namespace: String = "",
    val occupants: Int = 0,
    val organization: String = "",
    val popularity: Int = 0,
    val previewYoutubeId: String? = null,
    val privateOccupants: Int = 0,
    val publicOccupants: Int = 0,
    val publicationDate: String = "",
    val releaseStatus: String = "",
    val tags: List<String> = emptyList(),
    val thumbnailImageUrl: String = "",
    val unityPackages: List<UnityPackage> = emptyList(),
    @SerialName("updated_at") val updatedAt: String = "",
    val version: Int = 0,
    val visits: Int = 0,
)

@Serializable
data class UnityPackage(
    val assetUrl: String = "",
    val assetUrlObject: kotlinx.serialization.json.JsonElement? = null,
    val assetVersion: Int = 0,
    @SerialName("created_at") val createdAt: String = "",
    val id: String = "",
    val platform: String = "",
    val pluginUrl: String = "",
    val pluginUrlObject: kotlinx.serialization.json.JsonElement? = null,
    val unitySortNumber: Long = 0,
    val unityVersion: String = "",
    val variant: String = "",
    val worldSignature: String = "",
    val impostorUrl: String = "",
    val scanStatus: String = "",
    val performanceRating: String = "",
)

@Serializable
data class WorldSearchResult(
    val authorId: String = "",
    val authorName: String = "",
    val capacity: Int = 0,
    val description: String = "",
    val favorites: Int = 0,
    val heat: Int = 0,
    val id: String = "",
    val imageUrl: String = "",
    val name: String = "",
    val occupants: Int = 0,
    val popularity: Int = 0,
    val releaseStatus: String = "",
    val tags: List<String> = emptyList(),
    val thumbnailImageUrl: String = "",
)
