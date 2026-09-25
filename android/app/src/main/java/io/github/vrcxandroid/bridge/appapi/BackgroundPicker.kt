package io.github.vrcxandroid.bridge.appapi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Asks the user for something (a folder) without holding the AppApi lane. AppApi calls run one at a time, so a call
 * that waited for a picker would stall every later call (notification icons, the print queue, game state) until the
 * user answered, and for good if the answer never came back (the Activity died while the picker was open). The picker
 * runs in [scope] instead and the caller returns at once.
 *
 * One picker at a time: [launch] while one is open does nothing. [timeoutMillis] frees the slot when the answer is
 * lost.
 */
class BackgroundPicker<T : Any>(
    private val scope: CoroutineScope,
    private val pick: suspend () -> T?,
    private val log: (String, Throwable?) -> Unit,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    private val open = AtomicBoolean(false)

    /** True while a picker is open. */
    val isOpen: Boolean get() = open.get()

    /**
     * Starts the picker and returns true, or returns false when one is already open. [onPicked] runs in [scope] with
     * the answer; nothing runs when the user cancels.
     */
    fun launch(onPicked: (T) -> Unit): Boolean {
        if (!open.compareAndSet(false, true)) return false
        scope.launch {
            try {
                val picked = withTimeoutOrNull(timeoutMillis) { pick() }
                if (picked != null) onPicked(picked)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Picker failed", e)
            }
        }.invokeOnCompletion { open.set(false) } // also when the job is cancelled before it starts
        return true
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 15 * 60_000L
    }
}
