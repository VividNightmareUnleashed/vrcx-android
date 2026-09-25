package io.github.vrcxandroid

import android.app.Application

class VrcxApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }
}
