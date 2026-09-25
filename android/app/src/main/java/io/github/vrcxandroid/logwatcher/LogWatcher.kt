package io.github.vrcxandroid.logwatcher

import android.content.Context
import io.github.vrcxandroid.GameStateProvider

/** Placeholder: Kotlin port of Dotnet/LogWatcher.cs. */
class LogWatcher(private val context: Context) : GameStateProvider {
    override val isGameRunning: Boolean get() = false
    override val isSteamVRRunning: Boolean get() = false
    override val vrcClosedGracefully: Boolean get() = true
}
