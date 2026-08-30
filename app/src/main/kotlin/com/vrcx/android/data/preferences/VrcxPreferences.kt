package com.vrcx.android.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "vrcx_settings")

data class NotificationPolicy(val invites: Boolean, val friendRequests: Boolean, val general: Boolean) {
    companion object {
        val DISABLED = NotificationPolicy(invites = false, friendRequests = false, general = false)
    }
}

internal fun <T> Flow<T>.recoverIOExceptionWith(fallback: T): Flow<T> = catch { error ->
    if (error is IOException) emit(fallback) else throw error
}

/** Theme choices and their stable on-disk tokens. */
enum class ThemeMode(val token: String, val label: String) {
    SYSTEM("system", "System"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark"),
    ;

    companion object {
        fun fromToken(token: String?): ThemeMode? = entries.firstOrNull { it.token == token }
    }
}

/** Wallpaper scaling choices and their stable on-disk tokens. */
enum class WallpaperScaleMode(val token: String, val label: String) {
    CROP("crop", "Crop"),
    FIT("fit", "Fit"),
    FILL_WIDTH("fill_width", "Fill W"),
    FILL_HEIGHT("fill_height", "Fill H"),
    ;

    companion object {
        fun fromToken(token: String?): WallpaperScaleMode? = entries.firstOrNull { it.token == token }
    }
}

/** Every setting's value before the user has made a choice. */
object PreferenceDefaults {
    val THEME_MODE = ThemeMode.DARK
    const val DYNAMIC_COLORS = false
    val WALLPAPER_SCALE_MODE = WallpaperScaleMode.CROP
    const val NOTIFY_INVITE = true
    const val NOTIFY_FRIEND_REQUEST = true
    const val NOTIFY_GENERAL = true
    const val MAX_FEED_SIZE = 1000
    const val AUTO_LOGIN = false
    const val BACKGROUND_SERVICE_ENABLED = true
}

@Singleton
class VrcxPreferences private constructor(owners: VrcxPreferenceOwners) :
    NotificationPreferences by owners.notifications,
    AppearancePreferences by owners.appearance,
    GeneralPreferences by owners.general,
    SessionPreferences by owners.session {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        VrcxPreferenceOwners(PreferenceSource(context.dataStore)),
    )
}

private class VrcxPreferenceOwners(source: PreferenceSource) {
    val notifications: NotificationPreferences = StoredNotificationPreferences(source)
    val appearance: AppearancePreferences = StoredAppearancePreferences(source)
    val general: GeneralPreferences = StoredGeneralPreferences(source)
    val session: SessionPreferences = StoredSessionPreferences(source)
}
