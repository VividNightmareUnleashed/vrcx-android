package io.github.vrcxandroid.host

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.WindowManager
import io.github.vrcxandroid.AppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

/**
 * Process-wide host state (docs/ARCHITECTURE.md §6-7): the attached Activity, visibility, background mode, the pending
 * launch command, keep-screen-on requests, and the restart/quit sequences. Initialised by [AndroidHostServices].
 */
object VrcxHost {
    private const val TAG = "VRCXHost"
    private const val PREFS = "vrcx_host"
    private const val KEY_BACKGROUND_MODE = "background_mode"

    /** Background mode off: pause the WebView and the companion after this long hidden (ARCHITECTURE.md §7). */
    const val PAUSE_DELAY_MS = 60_000L

    lateinit var app: Application
        private set
    lateinit var prefs: SharedPreferences
        private set
    lateinit var paths: LocalPaths
        private set

    val main: Handler by lazy { Handler(Looper.getMainLooper()) }

    /** Native → JS event routing (sticky state + the page that connected last). */
    val events = EventRelay()

    @Volatile
    private var initialized = false

    fun init(app: Application) {
        if (initialized) return
        initialized = true
        this.app = app
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        paths = LocalPaths(File(app.filesDir, "local"), app.cacheDir)
        AppGraph.dispatcher.eventSink = events::deliver
        events.observe { name, text ->
            if (name == "companion-state") VrcxForegroundService.onCompanionState(text)
        }
        HostNotifications.ensureChannels(app)
        NetworkMonitor.start(app)
    }

    // ---- Activity and visibility ----

    @Volatile
    var activity: MainActivity? = null
        private set

    /** True while an Activity with the WebView is started (visible). */
    @Volatile
    var started = false
        private set

    /** The WebView and companion are paused because background mode is off and the app was hidden for 60 s. */
    @Volatile
    var pausedForBackground = false
        private set

    private var firstStart = true
    private var lastInsets: InsetsPayload? = null
    private val pauseRunnable = Runnable { pauseForBackground() }

    fun onActivityCreated(activity: MainActivity) {
        this.activity = activity
    }

    fun onActivityStarted(activity: MainActivity) {
        this.activity = activity
        started = true
        main.removeCallbacks(pauseRunnable)
        val wasPaused = pausedForBackground
        if (wasPaused) {
            pausedForBackground = false
            WebViewHolder.resumeAfterBackground()
        }
        if (wasPaused || firstStart) runCatching { AppGraph.companion.setRunning(true) }
        firstStart = false
        if (backgroundMode) VrcxForegroundService.start(app)
        HostNotifications.cancelAttention(app)
        applyKeepScreenOn()
        emit("visibility", buildJsonObject {
            put("visible", true)
            put("paused", wasPaused)
        })
    }

    fun onActivityStopped(activity: MainActivity) {
        if (this.activity !== activity) return
        started = false
        emit("visibility", buildJsonObject { put("visible", false) })
        if (!backgroundMode) schedulePause()
    }

    fun onActivityDestroyed(activity: MainActivity) {
        if (this.activity === activity) {
            this.activity = null
            if (activity.isFinishing) ActivityPickers.cancelPending()
        }
    }

    private fun schedulePause() {
        main.removeCallbacks(pauseRunnable)
        main.postDelayed(pauseRunnable, PAUSE_DELAY_MS)
    }

    private fun pauseForBackground() {
        if (started || backgroundMode || pausedForBackground) return
        Log.i(TAG, "background mode off and hidden for 60 s: pausing the WebView and the companion")
        pausedForBackground = true
        WebViewHolder.pauseForBackground()
        runCatching { AppGraph.companion.setRunning(false) }
    }

    // ---- Background mode (default on, ARCHITECTURE.md §7) ----

    var backgroundMode: Boolean
        get() = prefs.getBoolean(KEY_BACKGROUND_MODE, true)
        set(value) {
            prefs.edit().putBoolean(KEY_BACKGROUND_MODE, value).apply()
            main.post {
                if (value) {
                    main.removeCallbacks(pauseRunnable)
                    if (started) VrcxForegroundService.start(app)
                } else {
                    VrcxForegroundService.stop(app)
                    if (!started) schedulePause()
                }
            }
        }

    // ---- Events ----

    fun emit(event: String, data: JsonElement? = null) {
        AppGraph.dispatcher.emit(event, data)
    }

    /** Sends the `insets` event when the values changed. Main thread. */
    fun updateInsets(payload: InsetsPayload) {
        if (payload == lastInsets) return
        lastInsets = payload
        emit("insets", payload.toJson())
    }

    // ---- Launch commands (ARCHITECTURE.md §6.9) ----

    private val pendingLaunch = AtomicReference<String?>(null)

    fun setPendingLaunchCommand(command: String) {
        pendingLaunch.set(command)
    }

    /** Returns the pending command once, then "" (AppApi.GetLaunchCommand). */
    fun takeLaunchCommand(): String = pendingLaunch.getAndSet(null) ?: ""

    /** Running page: `launch-command` event. Page not loaded yet: pending for GetLaunchCommand after login. */
    fun deliverLaunchCommand(command: String) {
        if (events.isPageConnected) emit("launch-command", JsonPrimitive(command)) else setPendingLaunchCommand(command)
    }

    // ---- Keep screen on ----

    private val keepScreenOnReasons = HashSet<String>()

    fun setKeepScreenOn(reason: String, on: Boolean) {
        synchronized(keepScreenOnReasons) {
            if (on) keepScreenOnReasons += reason else keepScreenOnReasons -= reason
        }
        main.post { applyKeepScreenOn() }
    }

    private fun applyKeepScreenOn() {
        val window = activity?.window ?: return
        val on = synchronized(keepScreenOnReasons) { keepScreenOnReasons.isNotEmpty() }
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---- Intents ----

    /** Starts [intent] from the attached Activity (main thread), or with NEW_TASK from the application. */
    fun startIntent(intent: Intent): Boolean = onMainBlocking(false) {
        val a = activity?.takeUnless { it.isFinishing || it.isDestroyed }
        try {
            if (a != null) a.startActivity(intent)
            else app.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "startActivity refused", e)
            false
        }
    }

    /** Runs [block] on the main thread and waits for it (at most 5 s); runs inline on the main thread. */
    fun <T> onMainBlocking(fallback: T, block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val result = AtomicReference<T>(fallback)
        val latch = CountDownLatch(1)
        main.post {
            try {
                result.set(block())
            } catch (t: Throwable) {
                Log.w(TAG, "main-thread call failed", t)
            } finally {
                latch.countDown()
            }
        }
        latch.await(5, TimeUnit.SECONDS)
        return result.get()
    }

    // ---- Restart and quit ----

    @Volatile
    private var shuttingDown = false

    /** Drains the bridge, flushes VRCXStorage, cookies and the database, then restarts the process. */
    fun restartApp() = shutdown(restart = true)

    /** Notification "Quit": the same flush, then stops the service and ends the process. */
    fun quit() = shutdown(restart = false)

    private fun shutdown(restart: Boolean) {
        if (shuttingDown) return
        shuttingDown = true
        Log.i(TAG, if (restart) "restarting" else "quitting")
        WebViewHolder.blockBridge()
        AppGraph.scope.launch {
            flushAll()
            withContext(Dispatchers.Main) {
                if (restart) {
                    RestartActivity.start(app, Process.myPid())
                } else {
                    VrcxForegroundService.stop(app)
                    activity?.finishAndRemoveTask()
                }
            }
            Process.killProcess(Process.myPid())
            exitProcess(0)
        }
    }

    private suspend fun flushAll() {
        step("drain", 5_000) { AppGraph.dispatcher.drain() }
        step("storage", 3_000) { AppGraph.storage.flush() }
        step("cookies", 3_000) { AppGraph.http.flushCookies() }
        step("database", 3_000) { AppGraph.database.close() }
    }

    private suspend fun step(name: String, timeoutMs: Long, block: suspend () -> Unit) {
        try {
            if (withTimeoutOrNull(timeoutMs) { block(); true } == null) Log.w(TAG, "$name timed out")
        } catch (t: Throwable) {
            Log.w(TAG, "$name failed", t)
        }
    }
}
