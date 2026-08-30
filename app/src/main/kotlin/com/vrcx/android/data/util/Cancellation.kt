package com.vrcx.android.data.util

import kotlinx.coroutines.CancellationException

/**
 * [runCatching] that lets cancellation through instead of capturing it.
 *
 * `runCatching` and a bare `catch (e: Exception)` both swallow
 * [CancellationException], which leaves a coroutine running past the point it
 * was told to stop — and in this app a cancellation is also how an account
 * change aborts an in-flight load, so swallowing one trades a clean abort for a
 * cross-account write. Every best-effort step needs the same two-clause
 * preamble to avoid that, so it lives here once.
 */
suspend fun <T> runCatchingCancellable(block: suspend () -> T): Result<T> {
    val result = runCatching { block() }
    result.exceptionOrNull()
        ?.takeIf { it is CancellationException || it !is Exception }
        ?.let { throw it }
    return result
}

/** The failure [block] threw, or null if it succeeded. */
suspend fun captureFailure(block: suspend () -> Unit): Throwable? = runCatchingCancellable(block).exceptionOrNull()

/** For a step whose failure the caller has nothing to do about. */
suspend fun runIgnoringFailure(block: suspend () -> Unit) {
    runCatchingCancellable(block)
}
