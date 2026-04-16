package com.vrcx.android.data.api

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Event bus that carries auth-layer signals from network interceptors back to
 * AuthRepository without creating a dependency cycle (interceptors are built as
 * part of the OkHttpClient, which AuthRepository ultimately depends on via
 * Retrofit). ErrorInterceptor emits; AuthRepository collects.
 */
@Singleton
class AuthEventBus @Inject constructor() {
    private val _events = MutableSharedFlow<AuthEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<AuthEvent> = _events.asSharedFlow()

    fun tryEmit(event: AuthEvent) {
        _events.tryEmit(event)
    }
}

sealed class AuthEvent {
    /** The server rejected the current credentials. The session is gone server-side. */
    data object Unauthorized : AuthEvent()
}
