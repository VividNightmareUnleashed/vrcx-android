package com.vrcx.android.data.repository

import com.vrcx.android.data.api.model.Favorite
import com.vrcx.android.data.api.model.FavoriteGroup
import com.vrcx.android.data.api.model.FavoriteLimits

internal object FavoriteGroupSelector {
    fun preferredTags(
        type: String,
        limits: FavoriteLimits?,
        favorites: List<Favorite>,
        groups: List<FavoriteGroup>,
    ): List<String> {
        val groupLimit = limits?.maxFavoritesPerGroup?.get(type)
        val groupCounts = favorites
            .asSequence()
            .filter { it.type == type }
            .flatMap { it.tags.asSequence() }
            .groupingBy { it }
            .eachCount()

        val preferredTag = groupNames(type, limits, groups).firstOrNull { groupName ->
            groupLimit == null || groupCounts.getOrDefault(groupName, 0) < groupLimit
        } ?: DEFAULT_TAGS[type]

        return listOfNotNull(preferredTag)
    }

    fun defaultTags(type: String): List<String> = listOfNotNull(DEFAULT_TAGS[type])

    private fun groupNames(type: String, limits: FavoriteLimits?, groups: List<FavoriteGroup>): List<String> {
        val generatedNames = when (type) {
            "friend" -> (0 until (limits?.maxFavoriteGroups?.get("friend") ?: 1)).map { "group_$it" }
            "world" -> (1..(limits?.maxFavoriteGroups?.get("world") ?: 1)).map { "worlds$it" }
            "avatar" -> (1..(limits?.maxFavoriteGroups?.get("avatar") ?: 1)).map { "avatars$it" }
            else -> emptyList()
        }
        val savedNames = groups.asSequence()
            .filter { it.type == type }
            .map { it.name }
        return (savedNames + generatedNames.asSequence()).distinct().toList()
    }

    private val DEFAULT_TAGS = mapOf(
        "friend" to "group_0",
        "world" to "worlds1",
        "avatar" to "avatars1",
    )
}
