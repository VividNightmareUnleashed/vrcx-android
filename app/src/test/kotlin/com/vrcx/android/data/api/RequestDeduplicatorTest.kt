package com.vrcx.android.data.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

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

    @Test
    fun `clearCache cancels pending request waiters`() {
        runBlocking {
            val deduplicator = RequestDeduplicator()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()

            val owner = async {
                deduplicator.dedupGet("user:me") {
                    started.complete(Unit)
                    release.await()
                    "old-session"
                }
            }
            started.await()

            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                deduplicator.dedupGet("user:me") {
                    "new-session"
                }
            }

            deduplicator.clearCache()

            try {
                waiter.await()
                fail("Expected pending deduplicated request to be canceled")
            } catch (_: CancellationException) {
                // Expected.
            } finally {
                owner.cancelAndJoin()
            }
        }
    }

    @Test
    fun `cleared in-flight request cannot repopulate failure cache`() {
        runBlocking {
            val deduplicator = RequestDeduplicator()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val owner = async {
                try {
                    deduplicator.dedupGet("file:file_404") {
                        started.complete(Unit)
                        release.await()
                        throw HttpException(Response.error<Any>(404, "".toResponseBody(null)))
                    }
                } catch (_: HttpException) {
                    // Expected from the owner request.
                }
            }

            started.await()
            deduplicator.clearCache()
            release.complete(Unit)
            owner.await()

            assertNull(deduplicator.getCachedFailure("file:file_404"))
        }
    }
}
