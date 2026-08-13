package com.vrcx.android.data.api

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class BulkPaginatorTest {

    @Test
    fun `pages until a short page comes back`() = runTest {
        val requested = mutableListOf<Pair<Int, Int>>()

        val items = BulkPaginator.fetchAll(pageSize = 3, delayBetweenPagesMs = 0) { offset, count ->
            requested += offset to count
            page(offset, size = if (offset < 6) 3 else 2)
        }

        assertEquals(listOf(0 to 3, 3 to 3, 6 to 3), requested)
        assertEquals(8, items.size)
    }

    @Test
    fun `a content-aware stop ends paging on the matching page and still keeps it`() = runTest {
        var pages = 0

        val items = BulkPaginator.fetchAll(
            pageSize = 3,
            delayBetweenPagesMs = 0,
            stopWhen = { page, _ -> page.any { it == "item_4" } },
        ) { offset, _ ->
            pages++
            page(offset, size = 3)
        }

        assertEquals(2, pages)
        assertEquals(listOf("item_0", "item_1", "item_2", "item_3", "item_4", "item_5"), items)
    }

    @Test
    fun `maxPages bounds a source that never returns a short page`() = runTest {
        var pages = 0

        val items = BulkPaginator.fetchAll(pageSize = 2, maxPages = 3, delayBetweenPagesMs = 0) { offset, _ ->
            pages++
            page(offset, size = 2)
        }

        assertEquals(3, pages)
        assertEquals(6, items.size)
    }

    private fun page(offset: Int, size: Int): List<String> = (offset until offset + size).map { "item_$it" }
}
