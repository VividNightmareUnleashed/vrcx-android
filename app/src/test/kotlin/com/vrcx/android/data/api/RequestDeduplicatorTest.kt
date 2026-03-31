package com.vrcx.android.data.api

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RequestDeduplicatorTest {
    @Test
    fun `dedupGet shares the same in-flight request result`() {
        runBlocking {
            val deduplicator = RequestDeduplicator()
            var calls = 0

            coroutineScope {
                val first = async {
                    deduplicator.dedupGet("world:wrld_123") {
                        calls++
                        delay(50)
                        "shared-result"
                    }
                }
                val second = async {
                    deduplicator.dedupGet("world:wrld_123") {
                        calls++
                        "duplicate-result"
                    }
                }

                assertEquals("shared-result", first.await())
                assertEquals("shared-result", second.await())
            }

            assertEquals(1, calls)
        }
    }
}
