package io.github.vrcxandroid.host

import kotlinx.coroutines.withTimeoutOrNull

/**
 * The flush sequences of restart, quit and database import, separate from [VrcxHost]
 * so the order can be tested. Every step is bounded by a timeout and a failing step does not stop the ones after it.
 */
class ShutdownSteps(
    private val drain: suspend () -> Unit,
    private val flushStorage: suspend () -> Unit,
    private val flushCookies: suspend () -> Unit,
    private val closeDatabase: suspend () -> Unit,
    private val warn: (String, Throwable?) -> Unit = { _, _ -> },
) {
    /**
     * Restart or quit. With [saveState] the old process writes VRCXStorage and the cookie jar first. Without it (after
     * a database import) it only finishes the queued calls and closes the database, so its in-memory state cannot
     * overwrite the imported VRCX.json and `cookies` row.
     */
    suspend fun beforeExit(saveState: Boolean) {
        step("drain", DRAIN_TIMEOUT_MS, drain)
        if (saveState) {
            step("storage", FLUSH_TIMEOUT_MS, flushStorage)
            step("cookies", FLUSH_TIMEOUT_MS, flushCookies)
        }
        step("database", FLUSH_TIMEOUT_MS, closeDatabase)
    }

    /**
     * Before `DatabaseController.importFrom`: the queued calls finish, then VRCXStorage and the cookie jar are written
     * while they still belong to the files the import replaces. Nothing unsaved is left that a later timer could write
     * over the imported data.
     */
    suspend fun beforeImport() {
        step("drain", DRAIN_TIMEOUT_MS, drain)
        step("storage", FLUSH_TIMEOUT_MS, flushStorage)
        step("cookies", FLUSH_TIMEOUT_MS, flushCookies)
    }

    private suspend fun step(name: String, timeoutMs: Long, block: suspend () -> Unit) {
        try {
            if (withTimeoutOrNull(timeoutMs) { block(); true } == null) warn("$name timed out", null)
        } catch (t: Throwable) {
            warn("$name failed", t)
        }
    }

    companion object {
        const val DRAIN_TIMEOUT_MS = 5_000L
        const val FLUSH_TIMEOUT_MS = 3_000L
    }
}

/**
 * `AndroidHost.ImportDatabase` (docs/ARCHITECTURE.md §5.1): pick `VRCX.sqlite3`, optionally pick
 * `VRCX.json`, hold the page's bridge calls and save the current state, import, then restart without saving again
 * (success) or let the held calls through (failure). [F] is a picked document (a content Uri in the app), [R] the
 * import result (`{ok, message}`).
 */
class DatabaseImportFlow<F : Any, R>(
    private val pickDatabase: suspend () -> F?,
    private val pickJson: suspend () -> F?,
    /** Holds page calls and runs [ShutdownSteps.beforeImport]; false when a restart or quit is already running. */
    private val prepare: suspend () -> Boolean,
    private val import: suspend (database: F, json: F?) -> R,
    private val succeeded: (R) -> Boolean,
    /** Lets the held page calls through again. */
    private val resume: () -> Unit,
    /** Restarts without flushing VRCXStorage or the cookie jar ([ShutdownSteps.beforeExit] with saveState = false). */
    private val restartAfterImport: () -> Unit,
) {
    sealed interface Result<out R> {
        data object Cancelled : Result<Nothing>
        data object Busy : Result<Nothing>
        data class Done<R>(val value: R) : Result<R>
    }

    suspend fun run(): Result<R> {
        val database = pickDatabase() ?: return Result.Cancelled
        val json = pickJson()
        if (!prepare()) return Result.Busy
        val value = try {
            import(database, json)
        } catch (t: Throwable) {
            resume()
            throw t
        }
        if (succeeded(value)) restartAfterImport() else resume()
        return Result.Done(value)
    }
}
