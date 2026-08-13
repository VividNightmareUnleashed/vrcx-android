package com.vrcx.android.data.api

import kotlinx.coroutines.delay

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
     * @param stopWhen Optional early-exit predicate given the page just fetched and the
     *   running item count, for callers whose stop condition depends on either — a page
     *   overlapping items already held, or a server-reported total being reached. The
     *   matching page is still included.
     * @param fetcher Suspend function that takes (offset, count) and returns a list of items
     * @return Every item fetched, in page order
     */
    suspend fun <T> fetchAll(
        pageSize: Int = 100,
        maxPages: Int = 100,
        delayBetweenPagesMs: Long = 150L,
        stopOnShortPage: Boolean = true,
        stopWhen: (page: List<T>, fetchedSoFar: Int) -> Boolean = { _, _ -> false },
        fetcher: suspend (offset: Int, count: Int) -> List<T>,
    ): List<T> {
        val allItems = mutableListOf<T>()
        var offset = 0
        var page = 0

        while (page < maxPages) {
            val results = fetcher(offset, pageSize)
            allItems.addAll(results)
            if (results.isEmpty()) {
                break
            }
            if (stopOnShortPage && results.size < pageSize) {
                break
            }
            if (stopWhen(results, allItems.size)) {
                break
            }
            offset += results.size
            page++
            delay(delayBetweenPagesMs)
        }
        return allItems
    }
}
