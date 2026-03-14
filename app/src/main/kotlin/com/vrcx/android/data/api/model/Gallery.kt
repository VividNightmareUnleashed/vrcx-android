package com.vrcx.android.data.api.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class GalleryImage(
    @SerialName("created_at") val createdAt: String = "",
    val id: String = "",
    val ownerId: String = "",
    val versions: List<GalleryImageVersion> = emptyList(),
)

@Serializable
data class GalleryImageVersion(
    @SerialName("created_at") val createdAt: String = "",
    val delta: JsonElement? = null,
    val file: GalleryFile? = null,
    val signature: GalleryFile? = null,
    val status: String = "",
    val version: Int = 0,
)

@Serializable
data class GalleryFile(
    val category: String = "",
    val fileName: String = "",
    val md5: String = "",
    val sizeInBytes: Int = 0,
    val status: String = "",
    val uploadId: String = "",
    val url: String = "",
)

@Serializable
data class InventoryItem(
    val id: String = "",
    val inventoryId: String = "",
    val itemId: String = "",
    val expiresAfterUse: Boolean = false,
    val quantity: Int = 0,
    val usesLeft: Int = 0,
)

@Serializable
data class InventoryTemplate(
    val category: String = "",
    val description: String = "",
    val id: String = "",
    val imageUrl: String = "",
    val name: String = "",
    val tags: List<String> = emptyList(),
    val type: String = "",
)
