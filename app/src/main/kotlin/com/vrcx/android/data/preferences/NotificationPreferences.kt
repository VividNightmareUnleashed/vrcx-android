package com.vrcx.android.data.preferences

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface NotificationPreferences {
    val notifyInvite: Flow<Boolean>
    val notifyFriendRequest: Flow<Boolean>
    val notifyGeneral: Flow<Boolean>
    val notificationPolicy: Flow<NotificationPolicy>

    suspend fun setNotifyInvite(enabled: Boolean): Preferences
    suspend fun setNotifyFriendRequest(enabled: Boolean): Preferences
    suspend fun setNotifyGeneral(enabled: Boolean): Preferences
}

internal class StoredNotificationPreferences(private val source: PreferenceSource) : NotificationPreferences {
    override val notifyInvite: Flow<Boolean> = source.values.map {
        it[PreferenceKeys.notifyInvite] ?: PreferenceDefaults.NOTIFY_INVITE
    }
    override val notifyFriendRequest: Flow<Boolean> = source.values.map {
        it[PreferenceKeys.notifyFriendRequest] ?: PreferenceDefaults.NOTIFY_FRIEND_REQUEST
    }

    /** Covers notification types for which the app has no dedicated category. */
    override val notifyGeneral: Flow<Boolean> = source.values.map {
        it[PreferenceKeys.notifyGeneral] ?: PreferenceDefaults.NOTIFY_GENERAL
    }
    override val notificationPolicy: Flow<NotificationPolicy> = source.dataStore.data.map {
        NotificationPolicy(
            invites = it[PreferenceKeys.notifyInvite] ?: PreferenceDefaults.NOTIFY_INVITE,
            friendRequests = it[PreferenceKeys.notifyFriendRequest]
                ?: PreferenceDefaults.NOTIFY_FRIEND_REQUEST,
            general = it[PreferenceKeys.notifyGeneral] ?: PreferenceDefaults.NOTIFY_GENERAL,
        )
    }.recoverIOExceptionWith(NotificationPolicy.DISABLED)

    override suspend fun setNotifyInvite(enabled: Boolean): Preferences = source.dataStore.edit {
        it[PreferenceKeys.notifyInvite] = enabled
    }

    override suspend fun setNotifyFriendRequest(enabled: Boolean): Preferences = source.dataStore.edit {
        it[PreferenceKeys.notifyFriendRequest] = enabled
    }

    override suspend fun setNotifyGeneral(enabled: Boolean): Preferences = source.dataStore.edit {
        it[PreferenceKeys.notifyGeneral] = enabled
    }
}
