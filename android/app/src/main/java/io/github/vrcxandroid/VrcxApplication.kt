package io.github.vrcxandroid

import android.app.Application
import io.github.vrcxandroid.host.RestartActivity

class VrcxApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // The short-lived restart trampoline process (host/RestartActivity) must not open the database or start anything.
        if (RestartActivity.isRestartProcess(this)) return
        AppGraph.init(this)
    }
}
