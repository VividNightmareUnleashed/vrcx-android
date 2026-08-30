package com.vrcx.android.data.api

import com.vrcx.android.directTestDispatcher
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
            val deduplicator = RequestDeduplicator(directTestDispatcher)
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
            val deduplicator = RequestDeduplicator(directTestDispatcher)
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
    fun `clearCache cancels the request owner mid-flight`() {
        runBlocking {
            val deduplicator = RequestDeduplicator(directTestDispatcher)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val owner = async {
                deduplicator.dedupGet("file:file_404") {
                    started.complete(Unit)
                    release.await()
                    "old-session"
                }
            }

            started.await()
            deduplicator.clearCache()
            release.complete(Unit)
            try {
                owner.await()
                fail("Expected the stale request owner to be canceled")
            } catch (_: CancellationException) {
                // Expected: clearing request state cancels the owned work, not
                // only callers waiting on its shared result.
            }
        }
    }

    @Test
    fun `dedupGet leaves failure caching to the HTTP layer`() {
        runBlocking {
            val deduplicator = RequestDeduplicator(directTestDispatcher)
            var calls = 0

            repeat(2) {
                try {
                    deduplicator.dedupGet("user:usr_missing") {
                        calls++
                        throw HttpException(Response.error<Any>(404, "".toResponseBody(null)))
                    }
                } catch (_: HttpException) {
                    // Expected: the failure reaches the caller unchanged.
                }
            }

            // A second failure cache keyed by resource id would short-circuit the
            // retry here, and nothing outside a sign-out could ever clear it.
            assertEquals(2, calls)
            assertNull(deduplicator.getCachedFailure("user:usr_missing"))
        }
    }

    @Test
    fun `stale generation cannot cache a failure`() {
        val deduplicator = RequestDeduplicator(directTestDispatcher)
        val oldGeneration = deduplicator.currentGeneration()

        deduplicator.clearCache()

        assertEquals(false, deduplicator.cacheFailureIfCurrent("user:old", 404, oldGeneration))
        assertNull(deduplicator.getCachedFailure("user:old"))
    }
}
