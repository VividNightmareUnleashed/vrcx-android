package com.vrcx.android.data.repository

import com.vrcx.android.data.api.model.CurrentUser
import com.vrcx.android.data.api.model.Favorite
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ProfilePreferenceActionsTest {
    @Test
    fun `profile preference actions route through their state owners`() = runTest {
        val favoriteRepository = mock<FavoriteRepository>()
        val friendRepository = mock<FriendRepository>()
        whenever(favoriteRepository.addFavorite("friend", "usr_target")).thenReturn(
            Favorite(id = "fvrt_1", favoriteId = "usr_target", type = "friend"),
        )
        whenever(friendRepository.toggleFriendNotify("usr_target")).thenReturn(true)
        val actions = actions(favoriteRepository = favoriteRepository, friendRepository = friendRepository)

        actions.addFriendFavorite("usr_target")
        actions.deleteFavorite("fvrt_1")
        val notifyEnabled = actions.toggleNotify("usr_target")

        verify(favoriteRepository).addFavorite("friend", "usr_target")
        verify(favoriteRepository).deleteFavorite("fvrt_1")
        verify(friendRepository).toggleFriendNotify("usr_target")
        assertTrue(notifyEnabled)
    }

    @Test
    fun `note and memo writes preserve account-scoped local state`() = runTest {
        val userRepository = mock<UserRepository>()
        val localProfileRepository = mock<LocalUserProfileRepository>()
        whenever(userRepository.saveUserNote("usr_target", "hello")).thenReturn(JsonObject(emptyMap()))
        val actions = actions(
            userRepository = userRepository,
            localProfileRepository = localProfileRepository,
        )

        val noteSaved = actions.saveNote("usr_target", "Target", "hello")
        val memoSaved = actions.saveMemo("usr_target", "memo")

        verify(userRepository).saveUserNote("usr_target", "hello")
        verify(localProfileRepository).saveNote("usr_owner", "usr_target", "Target", "hello")
        verify(localProfileRepository).saveMemo("usr_owner", "usr_target", "memo")
        assertTrue(noteSaved)
        assertTrue(memoSaved)
    }

    private fun actions(
        favoriteRepository: FavoriteRepository = mock(),
        friendRepository: FriendRepository = mock(),
        userRepository: UserRepository = mock(),
        authRepository: AuthRepository = loggedInAuth(),
        localProfileRepository: LocalUserProfileRepository = mock(),
    ) = ProfilePreferenceActions(
        favoriteRepository = favoriteRepository,
        friendRepository = friendRepository,
        userRepository = userRepository,
        authRepository = authRepository,
        localProfileRepository = localProfileRepository,
    )

    private fun loggedInAuth(): AuthRepository = mock<AuthRepository>().also { repository ->
        whenever(repository.authState).thenReturn(
            MutableStateFlow(AuthState.LoggedIn(CurrentUser(id = "usr_owner"))),
        )
    }
}
