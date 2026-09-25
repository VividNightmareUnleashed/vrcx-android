package io.github.vrcxandroid

import android.app.Application
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
    lateinit var companion: CompanionController
        private set
    lateinit var tts: TtsController
        private set

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
        companion = CompanionManager(app)

        host = AndroidHostServices(app)
        tts = AndroidTtsController(app)

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
    }
}
