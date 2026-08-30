package com.vrcx.android.data.repository

import com.vrcx.android.data.api.model.VrcUser
import com.vrcx.android.data.api.model.displayAvatarUrl
import com.vrcx.android.data.cache.ProfilePicCacheManager
import com.vrcx.android.data.db.dao.FriendNotifyDao
import com.vrcx.android.data.db.dao.MemoDao
import com.vrcx.android.data.db.dao.NoteDao
import com.vrcx.android.data.db.dao.getMemo
import com.vrcx.android.data.db.dao.getNote
import com.vrcx.android.data.db.dao.isEnabled
import com.vrcx.android.data.db.dao.memosByUserId
import com.vrcx.android.data.db.dao.save
import com.vrcx.android.data.db.dao.saveMemo
import com.vrcx.android.data.util.captureFailure
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

data class LocalUserProfile(
    val note: String? = null,
    val memo: String? = null,
    val notifyEnabled: Boolean = false,
    val error: String? = null,
)

/** Owns local profile metadata and cached profile assets. */
@Singleton
class LocalUserProfileRepository @Inject constructor(
    private val noteDao: NoteDao,
    private val memoDao: MemoDao,
    private val friendNotifyDao: FriendNotifyDao,
    private val profilePicCacheManager: ProfilePicCacheManager,
) {
    suspend fun load(ownerId: String, userId: String, user: VrcUser): LocalUserProfile {
        var note: String? = null
        var memo: String? = null
        var notifyEnabled = false
        val error = captureFailure {
            memo = memoDao.getMemo(ownerId, userId)?.memo
            val localNote = noteDao.getNote(ownerId, userId)?.note
            note = user.note?.takeIf { it.isNotBlank() } ?: localNote
            if (!user.note.isNullOrBlank()) {
                saveNote(ownerId, userId, user.displayName, user.note)
            }
            notifyEnabled = friendNotifyDao.isEnabled(ownerId, userId)
        }?.message
        return LocalUserProfile(
            note = note,
            memo = memo,
            notifyEnabled = notifyEnabled,
            error = error,
        )
    }

    suspend fun cacheProfilePicture(user: VrcUser) {
        val imageUrl = user.displayAvatarUrl()
        if (imageUrl.isNotEmpty()) profilePicCacheManager.cacheImage(imageUrl)
    }

    suspend fun saveNote(ownerId: String, userId: String, displayName: String, text: String) {
        noteDao.save(
            ownerId = ownerId,
            userId = userId,
            displayName = displayName,
            note = text,
            createdAt = Instant.now().toString(),
        )
    }

    /** Every memo [ownerId] has saved, keyed by the user each one is about. */
    suspend fun loadMemos(ownerId: String): Map<String, String> = memoDao.memosByUserId(ownerId)

    suspend fun saveMemo(ownerId: String, userId: String, text: String) {
        memoDao.saveMemo(ownerId, userId, text, Instant.now().toString())
    }
}
