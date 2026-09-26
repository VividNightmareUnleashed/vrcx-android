package io.github.vrcxandroid

import android.app.Application
import io.github.vrcxandroid.host.LegacyAppCleanup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class VrcxApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        AppGraph.scope.launch(Dispatchers.IO) { LegacyAppCleanup.runOnce(this@VrcxApplication) }
    }
}
