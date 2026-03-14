package com.vrcx.android.data.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Coroutine-based paginated fetching, equivalent to desktop's processBulk().
 * Fetches pages of results until the server returns fewer items than requested.
 *
 * Reference: reference/src/services/request.js lines 390-461
 */
object BulkPaginator {

    /**
     * Fetch all items by paginating through API results.
     *
     * @param pageSize Number of items per page (default 100)
     * @param maxPages Maximum number of pages to fetch (safety limit)
     * @param fetcher Suspend function that takes (offset, count) and returns a list of items
     * @return Flow emitting each page of results
     */
    fun <T> paginate(
        pageSize: Int = 100,
        maxPages: Int = 100,
        fetcher: suspend (offset: Int, count: Int) -> List<T>,
    ): Flow<List<T>> = flow {
        var offset = 0
        var page = 0

        while (page < maxPages) {
            val results = fetcher(offset, pageSize)
            if (results.isNotEmpty()) {
                emit(results)
            }
            if (results.size < pageSize) {
                break
            }
            offset += results.size
            page++
        }
    }

    /**
     * Fetch all items and collect them into a single list.
     */
    suspend fun <T> fetchAll(
        pageSize: Int = 100,
        maxPages: Int = 100,
        fetcher: suspend (offset: Int, count: Int) -> List<T>,
    ): List<T> {
        val allItems = mutableListOf<T>()
        paginate(pageSize, maxPages, fetcher).collect { page ->
            allItems.addAll(page)
        }
        return allItems
    }
}
