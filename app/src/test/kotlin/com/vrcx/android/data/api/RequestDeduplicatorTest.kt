package com.vrcx.android.data.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
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
                owner.await()
                fail("Expected request owner to be canceled")
            } catch (_: CancellationException) {
                // Expected: the deduplicator owns and cancels the HTTP work.
            }

            try {
                waiter.await()
                fail("Expected pending deduplicated request to be canceled")
            } catch (_: CancellationException) {
                // Expected.
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
            try {
                owner.await()
                fail("Expected the stale request owner to be canceled")
            } catch (_: CancellationException) {
                // Expected: clearing request state now cancels the owned work,
                // not only callers waiting on its shared result.
            }

            assertNull(deduplicator.getCachedFailure("file:file_404"))
        }
    }

    @Test
    fun `stale generation cannot cache a failure`() {
        val deduplicator = RequestDeduplicator()
        val oldGeneration = deduplicator.currentGeneration()

        deduplicator.clearCache()

        assertEquals(false, deduplicator.cacheFailureIfCurrent("user:old", 404, oldGeneration))
        assertNull(deduplicator.getCachedFailure("user:old"))
    }
}
