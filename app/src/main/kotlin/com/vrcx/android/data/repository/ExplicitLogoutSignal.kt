package com.vrcx.android.data.repository

import java.util.concurrent.CopyOnWriteArraySet
import javax.inject.Inject
import javax.inject.Singleton

/** Synchronously clears process-memory credentials before logout reveals the login screen. */
@Singleton
class ExplicitLogoutSignal @Inject constructor() {
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun subscribe(listener: () -> Unit) {
        listeners += listener
    }

    fun unsubscribe(listener: () -> Unit) {
        listeners -= listener
    }

    internal fun publish() {
        listeners.forEach { listener -> runCatching(listener) }
    }
}
