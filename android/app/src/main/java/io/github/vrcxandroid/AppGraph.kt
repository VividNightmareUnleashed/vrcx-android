package io.github.vrcxandroid

import android.app.Application
import android.util.Log
import io.github.vrcxandroid.bridge.AndroidHostModule
import io.github.vrcxandroid.bridge.AssetBundleManagerModule
import io.github.vrcxandroid.bridge.BridgeDispatcher
import io.github.vrcxandroid.bridge.DiscordModule
import io.github.vrcxandroid.bridge.SQLiteModule
import io.github.vrcxandroid.bridge.VRCXStorageModule
import io.github.vrcxandroid.bridge.WebApiModule
import io.github.vrcxandroid.bridge.appapi.AppApiModule
import io.github.vrcxandroid.companion.CompanionManager
import io.github.vrcxandroid.host.AndroidHostServices
import io.github.vrcxandroid.host.AndroidTtsController
import io.github.vrcxandroid.host.HostServices
import io.github.vrcxandroid.logwatcher.LogWatcher
import io.github.vrcxandroid.logwatcher.LogWatcherModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Process-wide singletons. Modules take only a Context in their constructor and reach their collaborators through
 * this object (lazily, after [init] has run), so packages can be developed independently.
 */
object AppGraph {
    lateinit var app: Application
        private set

    /** Lives as long as the process. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var dispatcher: BridgeDispatcher
        private set
    lateinit var host: HostServices
        private set
    lateinit var storage: StorageController
        private set
    lateinit var database: DatabaseController
        private set
    lateinit var http: HttpProvider
        private set
    lateinit var logWatcher: LogWatcher
        private set
    lateinit var gameState: GameStateProvider
        private set

    /**
     * Built on the IO dispatcher right after [init] (EncryptedSharedPreferences, the Keystore and a network callback
     * are too slow for Application.onCreate); an earlier caller builds it or waits for it.
     */
    val companion: CompanionController get() = companionLazy.value
    private lateinit var companionLazy: Lazy<CompanionController>

    /** Built off the main thread like [companion] (it reads the cached voice list from disk). */
    val tts: TtsController get() = ttsLazy.value
    private lateinit var ttsLazy: Lazy<TtsController>

    fun init(app: Application) {
        this.app = app
        dispatcher = BridgeDispatcher(scope)

        // Order matters: VRCXStorage is loaded before SQLite and WebApi initialise (upstream Program.cs order).
        val storageModule = VRCXStorageModule(app)
        storage = storageModule
        val sqliteModule = SQLiteModule(app)
        database = sqliteModule
        val webApiModule = WebApiModule(app)
        http = webApiModule

        logWatcher = LogWatcher(app)
        gameState = logWatcher
        companionLazy = lazy { CompanionManager(app) }

        host = AndroidHostServices(app)
        ttsLazy = lazy { AndroidTtsController(app) }

        listOf(
            storageModule,
            sqliteModule,
            webApiModule,
            LogWatcherModule(logWatcher),
            AppApiModule(app),
            DiscordModule(),
            AssetBundleManagerModule(),
            AndroidHostModule(app),
        ).forEach(dispatcher::register)

        // The companion's connection loop still starts with the process, just off the main thread.
        scope.launch(Dispatchers.IO) {
            listOf("companion" to companionLazy, "tts" to ttsLazy).forEach { (name, holder) ->
                try {
                    holder.value
                } catch (e: Exception) {
                    Log.e(TAG, "cannot create the $name (${e.javaClass.simpleName})")
                }
            }
        }
    }

    private const val TAG = "VRCXAppGraph"
}
