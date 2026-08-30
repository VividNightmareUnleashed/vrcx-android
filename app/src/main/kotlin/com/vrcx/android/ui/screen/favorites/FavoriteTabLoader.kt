package com.vrcx.android.ui.screen.favorites

import com.vrcx.android.data.repository.FavoriteRepository
import com.vrcx.android.data.util.captureFailure
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope

internal data class FavoriteLoadOutcome(val hasFavoriteData: Boolean, val hasPartialFailure: Boolean)

internal class FavoriteTabLoader(private val repository: FavoriteRepository) {
    suspend fun load(tab: FavoritesTab, forceRefresh: Boolean): FavoriteLoadOutcome = when (tab) {
        FavoritesTab.FRIENDS -> loadFriends(forceRefresh)
        FavoritesTab.WORLDS -> loadWorlds(forceRefresh)
        FavoritesTab.AVATARS -> loadAvatars(forceRefresh)
    }

    private suspend fun loadFriends(forceRefresh: Boolean): FavoriteLoadOutcome = supervisorScope {
        val favorites = async {
            captureFailure {
                repository.loadFavorites(type = "friend", forceRefresh = forceRefresh)
            }
        }
        val groups = async {
            captureFailure { repository.loadFavoriteGroups(forceRefresh = forceRefresh) }
        }
        FavoriteLoadOutcome(
            hasFavoriteData = favorites.await() == null,
            hasPartialFailure = groups.await() != null,
        )
    }

    private suspend fun loadWorlds(forceRefresh: Boolean): FavoriteLoadOutcome = supervisorScope {
        val worlds = async {
            captureFailure {
                repository.loadFavorites(type = "world", forceRefresh = forceRefresh)
            }
        }
        val vrcPlusWorlds = async {
            captureFailure {
                repository.loadFavorites(type = "vrcPlusWorld", forceRefresh = forceRefresh)
            }
        }
        val groups = async {
            captureFailure { repository.loadFavoriteGroups(forceRefresh = forceRefresh) }
        }
        val details = async {
            captureFailure { repository.loadFavoriteWorldsBulk(forceRefresh = forceRefresh) }
        }
        val favoriteFailures = listOf(worlds.await(), vrcPlusWorlds.await())
        val optionalFailures = listOf(groups.await(), details.await())
        FavoriteLoadOutcome(
            hasFavoriteData = favoriteFailures.any { it == null },
            hasPartialFailure = (favoriteFailures + optionalFailures).any { it != null },
        )
    }

    private suspend fun loadAvatars(forceRefresh: Boolean): FavoriteLoadOutcome = supervisorScope {
        val favorites = async {
            captureFailure {
                repository.loadFavorites(type = "avatar", forceRefresh = forceRefresh)
            }
        }
        val groups = async {
            captureFailure { repository.loadFavoriteGroups(forceRefresh = forceRefresh) }
        }
        val details = async {
            captureFailure { repository.loadFavoriteAvatarsBulk(forceRefresh = forceRefresh) }
        }
        val optionalFailures = listOf(groups.await(), details.await())
        FavoriteLoadOutcome(
            hasFavoriteData = favorites.await() == null,
            hasPartialFailure = optionalFailures.any { it != null },
        )
    }
}
