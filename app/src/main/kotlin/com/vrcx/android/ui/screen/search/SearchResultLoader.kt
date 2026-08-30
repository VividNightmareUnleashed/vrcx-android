package com.vrcx.android.ui.screen.search

import com.vrcx.android.data.api.model.Avatar
import com.vrcx.android.data.api.model.World
import com.vrcx.android.data.repository.SearchRepository

internal class SearchResultLoader(private val repository: SearchRepository) {
    private data class WorldSearchKey(
        val query: String,
        val mode: WorldSearchMode,
        val includeLabs: Boolean,
        val tag: String,
    )

    private class FilteredWorldSession(val key: WorldSearchKey) {
        val matches = mutableListOf<World>()
        var sourceOffset = 0
        var exhausted = false
    }

    private data class RemoteAvatarCache(val query: String, val providerUrl: String, val items: List<Avatar>)

    private var remoteAvatarCache: RemoteAvatarCache? = null
    private var filteredWorldSession: FilteredWorldSession? = null

    fun resetWorldSession() {
        filteredWorldSession = null
    }

    fun resetRemoteAvatars() {
        remoteAvatarCache = null
    }

    suspend fun load(request: SearchUiState, useRemoteAvatarCache: Boolean, ensureCurrent: () -> Unit): SearchResult {
        val query = request.query.trim()
        val offset = request.currentOffset
        return when (request.selectedTab) {
            SearchTab.USERS -> {
                val items = repository.searchUsers(
                    query = query,
                    n = SEARCH_PAGE_SIZE + 1,
                    offset = offset,
                    searchByBio = request.searchUsersByBio,
                    sortByLastLogin = request.sortUsersByLastLogin,
                )
                SearchResult.Users(items.take(SEARCH_PAGE_SIZE), items.size > SEARCH_PAGE_SIZE)
            }

            SearchTab.WORLDS -> loadWorldPage(request, ensureCurrent)

            SearchTab.AVATARS -> loadAvatarPage(request, query, offset, useRemoteAvatarCache, ensureCurrent)

            SearchTab.GROUPS -> {
                val items = repository.searchGroups(query, n = SEARCH_PAGE_SIZE + 1, offset = offset)
                SearchResult.Groups(items.take(SEARCH_PAGE_SIZE), items.size > SEARCH_PAGE_SIZE)
            }
        }
    }

    private suspend fun loadAvatarPage(
        request: SearchUiState,
        query: String,
        offset: Int,
        useRemoteAvatarCache: Boolean,
        ensureCurrent: () -> Unit,
    ): SearchResult.Avatars {
        if (request.avatarSearchSource != AvatarSearchSource.REMOTE) {
            val items = repository.searchAvatars(query, n = SEARCH_PAGE_SIZE + 1, offset = offset)
            return SearchResult.Avatars(items.take(SEARCH_PAGE_SIZE), items.size > SEARCH_PAGE_SIZE)
        }
        val providerUrl = request.avatarProviderUrl.trim()
        val cached = remoteAvatarCache?.takeIf {
            it.query == query && it.providerUrl == providerUrl
        }
        val items = if (useRemoteAvatarCache && cached != null) {
            cached.items
        } else {
            repository.searchRemoteAvatars(query = query, providerUrl = providerUrl).also { fetched ->
                ensureCurrent()
                remoteAvatarCache = RemoteAvatarCache(query, providerUrl, fetched)
            }
        }
        return SearchResult.Avatars(
            items = items.drop(offset).take(SEARCH_PAGE_SIZE),
            hasMore = offset + SEARCH_PAGE_SIZE < items.size,
        )
    }

    private suspend fun loadWorldPage(request: SearchUiState, ensureCurrent: () -> Unit): SearchResult.Worlds {
        val query = request.query.trim()
        val mode = request.worldMode.name.lowercase()
        if (request.worldMode == WorldSearchMode.SEARCH || query.isBlank()) {
            filteredWorldSession = null
            val items = repository.searchWorlds(
                query = query,
                n = SEARCH_PAGE_SIZE + 1,
                offset = request.currentOffset,
                mode = mode,
                includeLabs = request.includeWorldLabs,
                tag = request.worldTag,
            )
            return SearchResult.Worlds(items.take(SEARCH_PAGE_SIZE), items.size > SEARCH_PAGE_SIZE)
        }

        val key = WorldSearchKey(query, request.worldMode, request.includeWorldLabs, request.worldTag)
        val session = filteredWorldSession?.takeIf { it.key == key }
            ?: FilteredWorldSession(key).also { filteredWorldSession = it }
        val targetMatchCount = request.currentOffset + SEARCH_PAGE_SIZE + 1
        while (session.matches.size < targetMatchCount && !session.exhausted) {
            val items = repository.searchWorlds(
                query = query,
                n = WORLD_SOURCE_PAGE_SIZE,
                offset = session.sourceOffset,
                mode = mode,
                includeLabs = request.includeWorldLabs,
                tag = request.worldTag,
            )
            ensureCurrent()
            if (items.isEmpty()) {
                session.exhausted = true
            } else {
                session.matches += items.filter { world ->
                    world.name.contains(query, ignoreCase = true) ||
                        world.authorName.contains(query, ignoreCase = true)
                }
                session.sourceOffset += items.size
                if (items.size < WORLD_SOURCE_PAGE_SIZE) session.exhausted = true
            }
        }
        return SearchResult.Worlds(
            items = session.matches.drop(request.currentOffset).take(SEARCH_PAGE_SIZE),
            hasMore = session.matches.size > request.currentOffset + SEARCH_PAGE_SIZE,
        )
    }
}

private const val WORLD_SOURCE_PAGE_SIZE = 50
