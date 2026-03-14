package com.vrcx.android.data.api.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Avatar(
    val authorId: String = "",
    val authorName: String = "",
    @SerialName("created_at") val createdAt: String = "",
    val description: String = "",
    val featured: Boolean = false,
    val id: String = "",
    val imageUrl: String = "",
    val name: String = "",
    val releaseStatus: String = "",
    val tags: List<String> = emptyList(),
    val thumbnailImageUrl: String = "",
    val unityPackages: List<UnityPackage> = emptyList(),
    @SerialName("updated_at") val updatedAt: String = "",
    val version: Int = 0,
)
