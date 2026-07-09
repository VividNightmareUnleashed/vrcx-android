package com.vrcx.android.data.api.model

import kotlinx.serialization.Serializable

@Serializable
data class Favorite(
    val favoriteId: String = "",
    val id: String = "",
    val tags: List<String> = emptyList(),
    val type: String = "",
)

@Serializable
data class FavoriteGroup(
    val displayName: String = "",
    val id: String = "",
    val name: String = "",
    val ownerDisplayName: String = "",
    val ownerId: String = "",
    val tags: List<String> = emptyList(),
    val type: String = "",
    val visibility: String = "",
)

@Serializable
data class FavoriteAddRequest(
    val type: String,
    val favoriteId: String,
    val tags: List<String>,
)

@Serializable
data class FavoriteGroupUpdateRequest(
    val displayName: String,
    val visibility: String,
    val tags: List<String>,
)

@Serializable
data class FavoriteLimits(
    val defaultMaxFavoriteGroups: Map<String, Int> = emptyMap(),
    val defaultMaxFavoritesPerGroup: Map<String, Int> = emptyMap(),
    val maxFavoriteGroups: Map<String, Int> = emptyMap(),
    val maxFavoritesPerGroup: Map<String, Int> = emptyMap(),
)
