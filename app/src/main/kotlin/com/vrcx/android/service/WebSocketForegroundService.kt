package com.vrcx.android.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.vrcx.android.data.preferences.VrcxPreferences
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

internal const val SERVICE_LOG_TAG = "WebSocketForegroundService"

/** Android lifecycle facade for the account-scoped websocket pipeline. */
@AndroidEntryPoint
class WebSocketForegroundService : Service() {

    @Inject internal lateinit var repositories: PipelineRepositories

    @Inject internal lateinit var transport: PipelineTransport

    @Inject lateinit var preferences: VrcxPreferences

    @Inject lateinit var notificationHelper: NotificationHelper

    @Inject lateinit var pipelineStateResynchronizer: PipelineStateResynchronizer

    private val serviceScope by lazy(LazyThreadSafetyMode.NONE) {
        CoroutineScope(SupervisorJob() + transport.dispatcher)
    }
    private val controller by lazy(LazyThreadSafetyMode.NONE) {
        PipelineServiceController(
            repositories = repositories,
            transport = transport,
            environment = PipelineServiceEnvironment(
                preferences = preferences,
                notificationHelper = notificationHelper,
                stateResynchronizer = pipelineStateResynchronizer,
                connectivityManager = getSystemService(ConnectivityManager::class.java),
            ),
            parentScope = serviceScope,
            onStop = ::stopAfterPipelineFailure,
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = when (intent?.action) {
            ACTION_START -> ServiceMode.FOREGROUND
            ACTION_START_NON_FOREGROUND -> ServiceMode.NON_FOREGROUND
            else -> PipelineServiceStateStore.mode(this).takeIf { it == ServiceMode.FOREGROUND }
        }
        if (mode == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        applyMode(mode)
        return if (mode == ServiceMode.FOREGROUND) START_STICKY else START_NOT_STICKY
    }

    private fun applyMode(mode: ServiceMode) {
        val previous = PipelineServiceStateStore.mode(this)
        PipelineServiceStateStore.set(this, mode)
        notificationHelper.cancelServiceReconnectRequired()
        if (mode == ServiceMode.FOREGROUND) {
            // Android requires foreground promotion before asynchronous startup work.
            startForeground(
                NotificationHelper.SERVICE_NOTIFICATION_ID,
                notificationHelper.createWebSocketServiceNotification(),
            )
        } else if (previous == ServiceMode.FOREGROUND) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        controller.start()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
            fgsType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC != 0
        ) {
            Log.w(SERVICE_LOG_TAG, "Foreground service dataSync timeout reached; waiting for foreground recovery")
            PipelineServiceStateStore.set(this, ServiceMode.TIMED_OUT, synchronous = true)
            notificationHelper.notifyServiceReconnectRequired()
            controller.clear()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
    }

    private fun stopAfterPipelineFailure() {
        if (PipelineServiceStateStore.mode(this) == ServiceMode.FOREGROUND) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    override fun onDestroy() {
        controller.close()
        // Preserve the timeout marker until the next foreground recovery attempt.
        if (PipelineServiceStateStore.mode(this) != ServiceMode.TIMED_OUT) {
            PipelineServiceStateStore.set(this, ServiceMode.NONE)
        }
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "com.vrcx.android.START_WEBSOCKET"
        private const val ACTION_START_NON_FOREGROUND = "com.vrcx.android.START_WEBSOCKET_NON_FOREGROUND"

        fun start(context: Context): Boolean {
            val intent = Intent(context, WebSocketForegroundService::class.java).apply {
                action = ACTION_START
            }
            return runCatching {
                context.startForegroundService(intent)
                true
            }.getOrElse {
                Log.w(SERVICE_LOG_TAG, "Unable to start foreground websocket service", it)
                false
            }
        }

        fun startNonForeground(context: Context): Boolean {
            val intent = Intent(context, WebSocketForegroundService::class.java).apply {
                action = ACTION_START_NON_FOREGROUND
            }
            return runCatching {
                context.startService(intent)
                true
            }.getOrElse {
                Log.w(SERVICE_LOG_TAG, "Unable to start non-foreground websocket service", it)
                false
            }
        }

        fun stop(context: Context): Boolean {
            // Explicit stop revokes a timeout recovery even after self-termination.
            runCatching { PipelineServiceStateStore.set(context, ServiceMode.NONE) }
            return runCatching {
                context.stopService(Intent(context, WebSocketForegroundService::class.java))
            }.getOrElse {
                Log.w(SERVICE_LOG_TAG, "Unable to stop websocket service", it)
                false
            }
        }

        fun restartAfterTimeoutIfNeeded(context: Context): Boolean =
            PipelineServiceStateStore.mode(context) == ServiceMode.TIMED_OUT && start(context)
    }
}
