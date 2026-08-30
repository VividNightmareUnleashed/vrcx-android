package com.vrcx.android.data.repository

import com.vrcx.android.data.api.model.VrcUser
import com.vrcx.android.data.cache.ProfilePicCacheManager
import com.vrcx.android.data.db.dao.FriendNotifyDao
import com.vrcx.android.data.db.dao.MemoDao
import com.vrcx.android.data.db.dao.NoteDao
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

class LocalUserProfileRepositoryTest {
    @Test
    fun `profile picture caching uses the preferred image and ignores an absent image`() = runTest {
        val cacheManager = mock<ProfilePicCacheManager>()
        val repository = LocalUserProfileRepository(
            noteDao = mock<NoteDao>(),
            memoDao = mock<MemoDao>(),
            friendNotifyDao = mock<FriendNotifyDao>(),
            profilePicCacheManager = cacheManager,
        )

        repository.cacheProfilePicture(
            VrcUser(
                profilePicOverrideThumbnail = "https://example.com/preferred.png",
                profilePicOverride = "https://example.com/fallback.png",
                currentAvatarThumbnailImageUrl = "https://example.com/avatar.png",
            ),
        )
        repository.cacheProfilePicture(VrcUser())

        verify(cacheManager, times(1)).cacheImage("https://example.com/preferred.png")
    }
}
