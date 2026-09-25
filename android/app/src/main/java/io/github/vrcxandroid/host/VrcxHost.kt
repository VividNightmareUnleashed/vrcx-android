package io.github.vrcxandroid.host

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ComponentName
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
        if (backgroundMode && hasPage) VrcxForegroundService.start(app)
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

    /** False behind the WebView gate screen: then there is no page for the service to keep alive. */
    private val hasPage: Boolean get() = WebViewHolder.current != null

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
                    if (started && hasPage) VrcxForegroundService.start(app)
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

    private val launchInbox = LaunchCommandInbox()

    fun setPendingLaunchCommand(command: String) = launchInbox.setPending(command)

    /** Returns the pending command once, then "" (AppApi.GetLaunchCommand). */
    fun takeLaunchCommand(): String = launchInbox.take()

    /** Running page: `launch-command` event. Page not loaded yet: pending for GetLaunchCommand after login. */
    fun deliverLaunchCommand(command: String) {
        launchInbox.deliver(command, events.isPageConnected) { emit("launch-command", JsonPrimitive(it)) }
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

    // ---- Restart, quit and database import ----

    @Volatile
    private var shuttingDown = false

    private val steps by lazy {
        ShutdownSteps(
            drain = {
                WebViewHolder.awaitInbound()
                AppGraph.dispatcher.drain()
            },
            flushStorage = { AppGraph.storage.flush() },
            flushCookies = { AppGraph.http.flushCookies() },
            closeDatabase = { AppGraph.database.close() },
            warn = { message, t -> Log.w(TAG, message, t) },
        )
    }

    /** Drains the bridge, flushes VRCXStorage, cookies and the database, then restarts the process. */
    fun restartApp() = shutdown(restart = true, saveState = true)

    /** Notification "Quit": the same flush, then stops the service and ends the process. */
    fun quit() = shutdown(restart = false, saveState = true)

    /**
     * Database import, before `importFrom`: holds the page's bridge calls (queued, not dropped, in case the import
     * fails), finishes the queued ones and saves VRCXStorage and the cookie jar into the files the import replaces.
     * False when a restart or quit is already running.
     */
    suspend fun prepareDatabaseImport(): Boolean {
        if (shuttingDown || !WebViewHolder.holdBridge()) return false
        steps.beforeImport()
        return true
    }

    /** The import failed: the held page calls go through. */
    fun resumeAfterFailedImport() = WebViewHolder.releaseBridge()

    /**
     * The import succeeded: restart without flushing VRCXStorage or the cookie jar, which would write this process's
     * pre-import state over the imported VRCX.json and `cookies` row.
     */
    fun restartAfterImport() = shutdown(restart = true, saveState = false)

    private fun shutdown(restart: Boolean, saveState: Boolean) {
        if (shuttingDown) return
        shuttingDown = true
        Log.i(TAG, if (restart) "restarting" else "quitting")
        WebViewHolder.blockBridge()
        AppGraph.scope.launch {
            steps.beforeExit(saveState)
            withContext(Dispatchers.Main) {
                if (restart) {
                    relaunch()
                } else {
                    VrcxForegroundService.stop(app)
                    activity?.finishAndRemoveTask()
                }
                // Still on the main thread, so the relaunched MainActivity cannot start in this process: the system
                // starts a fresh process for it, where every native singleton, the proxy and the database are new.
                Process.killProcess(Process.myPid())
                exitProcess(0)
            }
        }
    }

    /** `Intent.makeRestartActivityTask`: clears the task and starts MainActivity again. Main thread. */
    private fun relaunch() {
        val intent = Intent.makeRestartActivityTask(ComponentName(app, MainActivity::class.java))
        val a = activity?.takeUnless { it.isFinishing || it.isDestroyed }
        try {
            if (a != null) a.startActivity(intent) else app.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "could not relaunch", e)
        }
    }
}
