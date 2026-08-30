package com.vrcx.android.service

import android.net.ConnectivityManager
import com.vrcx.android.data.preferences.VrcxPreferences
import com.vrcx.android.data.repository.AccountScope
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.FriendRepository
import com.vrcx.android.data.repository.GalleryRepository
import com.vrcx.android.data.repository.GroupRepository
import com.vrcx.android.data.repository.NotificationRepository
import com.vrcx.android.data.websocket.PipelineOkHttpClient
import com.vrcx.android.di.IoDispatcher
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.json.Json

internal class PipelineRepositories @Inject constructor(
    val auth: AuthRepository,
    val accountScope: AccountScope,
    val friends: FriendRepository,
    val notifications: NotificationRepository,
    val groups: GroupRepository,
    val gallery: GalleryRepository,
)

internal class PipelineTransport @Inject constructor(
    val json: Json,
    val client: PipelineOkHttpClient,
    @IoDispatcher val dispatcher: CoroutineDispatcher,
)

internal data class PipelineServiceEnvironment(
    val preferences: VrcxPreferences,
    val notificationHelper: NotificationHelper,
    val stateResynchronizer: PipelineStateResynchronizer,
    val connectivityManager: ConnectivityManager,
)
