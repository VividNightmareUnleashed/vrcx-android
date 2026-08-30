package com.vrcx.android.data.repository

import com.vrcx.android.data.api.AvatarApi
import com.vrcx.android.data.api.GroupApi
import com.vrcx.android.data.api.RequestDeduplicator
import com.vrcx.android.data.api.model.Avatar
import com.vrcx.android.data.api.model.CurrentUser
import com.vrcx.android.data.api.model.Group
import com.vrcx.android.data.api.model.VrcUser
import com.vrcx.android.data.cache.ProfilePicCacheManager
import com.vrcx.android.data.db.dao.FriendNotifyDao
import com.vrcx.android.data.db.dao.MemoDao
import com.vrcx.android.data.db.dao.NoteDao
import com.vrcx.android.data.db.entity.FriendNotifyEntity
import com.vrcx.android.data.db.entity.MemoEntity
import com.vrcx.android.data.db.entity.NoteEntity
import com.vrcx.android.directTestDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class UserDetailRepositoryTest {
    @Test
    fun `profile group and avatar tabs paginate through all available results`() = runTest {
        val avatarApi = mock<AvatarApi>()
        val groupApi = mock<GroupApi>()
        // The endpoint returns membership objects: `id` is the membership,
        // `groupId` is the group itself.
        whenever(groupApi.getUserGroups("usr_target", 100, 0)).thenReturn(
            List(100) { Group(id = "gmem_$it", groupId = "grp_$it") },
        )
        whenever(groupApi.getUserGroups("usr_target", 100, 100)).thenReturn(
            listOf(Group(id = "gmem_100", groupId = "grp_100")),
        )
        whenever(
            avatarApi.getAvatars(n = 100, offset = 0, user = "usr_target", releaseStatus = "all"),
        ).thenReturn(List(100) { Avatar(id = "avtr_$it") })
        whenever(
            avatarApi.getAvatars(n = 100, offset = 100, user = "usr_target", releaseStatus = "all"),
        ).thenReturn(listOf(Avatar(id = "avtr_100")))
        val repository = repository(
            avatarApi = avatarApi,
            groupApi = groupApi,
        )

        val groups = repository.loadGroups("usr_target")
        assertEquals(101, groups.size)
        assertEquals("grp_100", groups.last().canonicalGroupId())
        assertEquals(101, repository.loadAvatars("usr_target").size)
    }

    @Test
    fun `remote note wins over local note and is synchronized locally`() = runTest {
        val userRepository = mock<UserRepository>()
        val noteDao = mock<NoteDao>()
        val memoDao = mock<MemoDao>()
        val notifyDao = mock<FriendNotifyDao>()
        val user = VrcUser(id = "usr_target", displayName = "Target", note = "remote note")
        whenever(userRepository.getUser("usr_target", forceRefresh = true)).thenReturn(user)
        whenever(noteDao.get("usr_owner:usr_target")).thenReturn(
            NoteEntity(note = "local note"),
        )
        whenever(memoDao.getMemo("usr_owner:usr_target")).thenReturn(
            MemoEntity(memo = "memo"),
        )
        whenever(notifyDao.get("usr_owner:usr_target")).thenReturn(
            FriendNotifyEntity(friendUserId = "usr_target"),
        )
        val repository = repository(
            userRepository = userRepository,
            noteDao = noteDao,
            memoDao = memoDao,
            friendNotifyDao = notifyDao,
        )

        val profile = repository.loadProfile("usr_target")

        assertEquals("remote note", profile.note)
        assertEquals("memo", profile.memo)
        assertTrue(profile.notifyEnabled)
        val savedNote = argumentCaptor<NoteEntity>()
        org.mockito.kotlin.verify(noteDao).insert(savedNote.capture())
        assertEquals("usr_owner:usr_target", savedNote.firstValue.compositeId)
        assertEquals("remote note", savedNote.firstValue.note)
    }

    @Test
    fun `memos are keyed by the user they are about, not by the composite key`() = runTest {
        val memoDao = mock<MemoDao>()
        whenever(memoDao.getMemos("usr_owner")).thenReturn(
            listOf(
                MemoEntity(odUserId = "usr_owner:usr_a", ownerUserId = "usr_owner", memo = "note a"),
                MemoEntity(odUserId = "usr_owner:usr_b", ownerUserId = "usr_owner", memo = "note b"),
            ),
        )

        assertEquals(
            mapOf("usr_a" to "note a", "usr_b" to "note b"),
            repository(memoDao = memoDao).loadMemos("usr_owner"),
        )
    }

    private fun repository(
        userRepository: UserRepository = mock(),
        avatarApi: AvatarApi = mock(),
        groupApi: GroupApi = mock(),
        noteDao: NoteDao = mock(),
        memoDao: MemoDao = mock(),
        friendNotifyDao: FriendNotifyDao = mock(),
    ): UserDetailRepository {
        val authRepository = mock<AuthRepository>()
        whenever(authRepository.authState).thenReturn(
            MutableStateFlow(AuthState.LoggedIn(CurrentUser(id = "usr_owner"))),
        )
        return UserDetailRepository(
            userRepository = userRepository,
            avatarRepository = AvatarRepository(
                avatarApi,
                RequestDeduplicator(directTestDispatcher),
                AccountScope(),
            ),
            groupRepository = GroupRepository(
                groupApi,
                RequestDeduplicator(directTestDispatcher),
                AccountScope(),
                directTestDispatcher,
            ),
            profileWorldLoader = mock(),
            authRepository = authRepository,
            localProfileRepository = LocalUserProfileRepository(
                noteDao = noteDao,
                memoDao = memoDao,
                friendNotifyDao = friendNotifyDao,
                profilePicCacheManager = mock<ProfilePicCacheManager>(),
            ),
        )
    }
}
