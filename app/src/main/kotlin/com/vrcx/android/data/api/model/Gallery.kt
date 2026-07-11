package com.vrcx.android.data.api.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class GalleryImage(
    @SerialName("created_at") val createdAt: String = "",
    val id: String = "",
    val name: String = "",
    val ownerId: String = "",
    val tags: List<String> = emptyList(),
    val versions: List<GalleryImageVersion> = emptyList(),
)

fun GalleryImage.imageUrl(): String? = versions.lastOrNull()?.file?.url

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
    val sizeInBytes: Long = 0,
    val status: String = "",
    val uploadId: String = "",
    val url: String = "",
)

@Serializable
data class VrcPrint(
    val id: String = "",
    val ownerId: String = "",
    val authorId: String = "",
    val authorName: String = "",
    val worldId: String = "",
    val worldName: String = "",
    val note: String = "",
    val files: PrintFiles = PrintFiles(),
    val createdAt: String = "",
)

@Serializable
data class PrintFiles(
    val image: String = "",
)

@Serializable
data class InventoryItem(
    val collections: List<String> = emptyList(),
    @SerialName("created_at") val createdAt: String = "",
    val defaultAttributes: JsonElement? = null,
    val description: String = "",
    val equipSlot: String? = null,
    val equipSlots: List<String> = emptyList(),
    val expiryDate: String? = null,
    val flags: List<String> = emptyList(),
    val holderId: String = "",
    val id: String = "",
    val imageUrl: String = "",
    val isArchived: Boolean = false,
    val isSeen: Boolean = false,
    val itemType: String = "",
    val itemTypeLabel: String = "",
    val metadata: JsonElement? = null,
    val name: String = "",
    val quantifiable: Boolean = false,
    val tags: List<String> = emptyList(),
    val templateId: String = "",
    @SerialName("template_created_at") val templateCreatedAt: String = "",
    @SerialName("template_updated_at") val templateUpdatedAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    val userAttributes: JsonElement? = null,
    val validateUserAttributes: Boolean = false,
)

@Serializable
data class InventoryTemplate(
    val authorId: String = "",
    val collections: List<String> = emptyList(),
    @SerialName("created_at") val createdAt: String = "",
    val defaultAttributes: JsonElement? = null,
    val description: String = "",
    val equipSlots: List<String> = emptyList(),
    val flags: List<String> = emptyList(),
    val id: String = "",
    val imageUrl: String = "",
    val itemType: String = "",
    val itemTypeLabel: String = "",
    val metadata: JsonElement? = null,
    val name: String = "",
    val notificationDetails: JsonElement? = null,
    val status: String = "",
    val tags: List<String> = emptyList(),
    @SerialName("updated_at") val updatedAt: String = "",
    val validateUserAttributes: Boolean = false,
)

@Serializable
data class InventoryResponse(
    val data: List<InventoryItem> = emptyList(),
    val totalCount: Int = 0,
)
