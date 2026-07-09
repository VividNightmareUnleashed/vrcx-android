package com.vrcx.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vrcx.android.MainActivity
import com.vrcx.android.R
import com.vrcx.android.data.preferences.VrcxPreferences
import com.vrcx.android.data.security.SecureSecretsStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

class BootReconnectWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val notificationHelper = NotificationHelper(applicationContext)

        // Run eligibility gates BEFORE the Android-15 notification branch.
        // Without this, logged-out users or users who disabled the background
        // service would still get a "tap to reconnect" notification on every
        // reboot on Android 15+.
        val preferences = VrcxPreferences(applicationContext)
        if (!preferences.backgroundServiceEnabled.first()) {
            notificationHelper.cancelBootReconnectRequired()
            return Result.success()
        }

        val legacyPrefs = applicationContext.getSharedPreferences("vrcx_cookies", Context.MODE_PRIVATE)
        val hasLegacyAuth = legacyPrefs.all.values.any { (it as? String)?.contains("auth=") == true }
        val secureSecretsStore = SecureSecretsStore(
            context = applicationContext,
            json = Json { ignoreUnknownKeys = true },
        )
        if (!secureSecretsStore.hasAuthCookie() && !hasLegacyAuth) {
            notificationHelper.cancelBootReconnectRequired()
            return Result.success()
        }

        // Android 15+ restricts foreground-service starts from BOOT_COMPLETED,
        // so we surface a "tap to reconnect" notification instead of trying to
        // start the websocket service here. This only fires once the gates
        // above confirm the user is logged in AND hasn't opted out.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            notificationHelper.notifyBootReconnectRequired()
            return Result.success()
        }

        notificationHelper.cancelBootReconnectRequired()
        setForeground(createForegroundInfo())
        return if (WebSocketForegroundService.start(applicationContext)) {
            Result.success()
        } else {
            Result.retry()
        }
    }

    private fun createForegroundInfo(): ForegroundInfo {
        ensureNotificationChannel()

        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = Notification.Builder(applicationContext, WebSocketForegroundService.CHANNEL_SERVICE)
            .setContentTitle("VRCX")
            .setContentText("Restoring background connection")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        return ForegroundInfo(
            WORKER_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            WebSocketForegroundService.CHANNEL_SERVICE,
            "Background Service",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "boot-reconnect-worker"
        private const val WORKER_NOTIFICATION_ID = 1001

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<BootReconnectWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }
}
