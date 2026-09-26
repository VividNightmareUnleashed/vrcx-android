package io.github.vrcxandroid.host

/**
 * Decides when [VrcxForegroundService] runs (docs/ARCHITECTURE.md §7): only while background mode is on, a page exists,
 * and there is something to keep alive, that is a VRChat session the page reported (`AndroidHost.SetSessionActive`) or
 * a connected PC companion. Without either (login page, logged out, no companion) the process may be cached and frozen
 * like any other app, and no notification is shown.
 *
 * - While the app is visible the service follows the condition at once. It is started while visible because a
 *   started service keeps running when the user leaves the app, and Android 12+ refuses to start one from the
 *   background.
 * - While hidden, a service that is no longer needed is stopped after [stopGraceMs]: a page reload reports the session
 *   again once auto-login has run, and a companion that dropped its connection usually reconnects within that time.
 *   Stopping at once would end background mode for good, since it cannot be started again until the app is opened.
 * - [holdForBoot] (start on boot) counts as needed for [bootHoldMs], which gives the page time to log in, or until the
 *   page reports a session or a companion connects.
 *
 * Pure JVM code, main thread only; [Control] connects it to Android.
 */
class ServiceGovernor(
    private val control: Control,
    private val stopGraceMs: Long = STOP_GRACE_MS,
    private val bootHoldMs: Long = BOOT_HOLD_MS,
) {
    interface Control {
        val backgroundMode: Boolean

        /** False behind the WebView gate screen: then there is no page to keep alive. */
        val hasPage: Boolean

        /** Running or starting. */
        val isRunning: Boolean

        /** Starts the service, or refreshes its notification when it runs. */
        fun start()
        fun stop()
        fun postDelayed(task: Runnable, delayMs: Long)
        fun cancel(task: Runnable)
    }

    var sessionActive = false
        private set
    var companionConnected = false
        private set
    var visible = false
        private set
    var bootHold = false
        private set

    /** True while a delayed stop is scheduled. */
    var stopPending = false
        private set

    private val stopTask = Runnable {
        stopPending = false
        if (!shouldRun() && !visible) control.stop() else update()
    }

    private val bootTask = Runnable {
        bootHold = false
        update()
    }

    fun shouldRun(): Boolean =
        control.backgroundMode && control.hasPage && (sessionActive || companionConnected || bootHold)

    /** Visible again: a running service also gets its notification posted again (it may have been hidden before). */
    fun setVisible(value: Boolean) {
        visible = value
        update(refresh = value)
    }

    fun setSessionActive(value: Boolean) {
        if (value) endBootHold()
        if (sessionActive == value) return
        sessionActive = value
        update()
    }

    fun setCompanionConnected(value: Boolean) {
        if (value) endBootHold()
        if (companionConnected == value) return
        companionConnected = value
        update()
    }

    /** The app was started at boot without an Activity: keep the service while the page logs in. */
    fun holdForBoot() {
        bootHold = true
        control.cancel(bootTask)
        control.postDelayed(bootTask, bootHoldMs)
        update()
    }

    /**
     * Re-evaluates after any input changed (background mode, the page, visibility, session, companion). [refresh]
     * also calls [Control.start] when the service already runs.
     */
    fun update(refresh: Boolean = false) {
        if (!control.backgroundMode && bootHold) endBootHold()
        if (shouldRun()) {
            cancelStop()
            if (refresh || !control.isRunning) control.start()
            return
        }
        if (!control.isRunning) {
            cancelStop()
            return
        }
        if (!control.backgroundMode || visible) {
            cancelStop()
            control.stop()
            return
        }
        if (!stopPending) {
            stopPending = true
            control.postDelayed(stopTask, stopGraceMs)
        }
    }

    private fun endBootHold() {
        if (!bootHold) return
        bootHold = false
        control.cancel(bootTask)
    }

    private fun cancelStop() {
        if (!stopPending) return
        stopPending = false
        control.cancel(stopTask)
    }

    companion object {
        const val STOP_GRACE_MS = 90_000L
        const val BOOT_HOLD_MS = 180_000L
    }
}
