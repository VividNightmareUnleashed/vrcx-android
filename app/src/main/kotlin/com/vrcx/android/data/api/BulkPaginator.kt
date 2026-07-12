package com.vrcx.android.data.api

import kotlinx.coroutines.delay
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
     * @param delayBetweenPagesMs Delay between page fetches to avoid rate limiting (default 150ms)
     * @param stopOnShortPage Stop as soon as a page returns fewer than [pageSize] items
     *   (default true). Set false for endpoints that return partial pages while more
     *   data remains and instead signal completion via an empty page or [stopWhen].
     * @param stopWhen Optional early-exit predicate given the running item count fetched
     *   so far (e.g. stop once a server-reported total is reached)
     * @param fetcher Suspend function that takes (offset, count) and returns a list of items
     * @return Flow emitting each page of results
     */
    fun <T> paginate(
        pageSize: Int = 100,
        maxPages: Int = 100,
        delayBetweenPagesMs: Long = 150L,
        stopOnShortPage: Boolean = true,
        stopWhen: (fetchedSoFar: Int) -> Boolean = { false },
        fetcher: suspend (offset: Int, count: Int) -> List<T>,
    ): Flow<List<T>> = flow {
        var offset = 0
        var page = 0
        var fetched = 0

        while (page < maxPages) {
            val results = fetcher(offset, pageSize)
            if (results.isNotEmpty()) {
                emit(results)
            }
            fetched += results.size
            if (results.isEmpty()) {
                break
            }
            if (stopOnShortPage && results.size < pageSize) {
                break
            }
            if (stopWhen(fetched)) {
                break
            }
            offset += results.size
            page++
            delay(delayBetweenPagesMs)
        }
    }

    /**
     * Fetch all items and collect them into a single list.
     */
    suspend fun <T> fetchAll(
        pageSize: Int = 100,
        maxPages: Int = 100,
        delayBetweenPagesMs: Long = 150L,
        stopOnShortPage: Boolean = true,
        stopWhen: (fetchedSoFar: Int) -> Boolean = { false },
        fetcher: suspend (offset: Int, count: Int) -> List<T>,
    ): List<T> {
        val allItems = mutableListOf<T>()
        paginate(pageSize, maxPages, delayBetweenPagesMs, stopOnShortPage, stopWhen, fetcher).collect { page ->
            allItems.addAll(page)
        }
        return allItems
    }
}
