package com.vrcx.android.ui.screen

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.vrcx.android.data.cache.ProfilePicCacheManager
import com.vrcx.android.data.preferences.VrcxPreferences
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AvatarRepository
import com.vrcx.android.data.repository.FavoriteRepository
import com.vrcx.android.data.repository.FeedRepository
import com.vrcx.android.data.repository.FriendRepository
import com.vrcx.android.data.repository.GalleryRepository
import com.vrcx.android.data.repository.GroupRepository
import com.vrcx.android.data.repository.InviteMessageRepository
import com.vrcx.android.data.repository.ModerationRepository
import com.vrcx.android.data.repository.NotificationRepository
import com.vrcx.android.data.repository.SearchRepository
import com.vrcx.android.data.repository.UserActionPerformer
import com.vrcx.android.data.repository.UserDetailRepository
import com.vrcx.android.data.repository.UserRepository
import com.vrcx.android.data.repository.WorldRepository
import com.vrcx.android.data.security.SecureSecretsStore
import com.vrcx.android.ui.screen.avatars.AvatarDetailScreen
import com.vrcx.android.ui.screen.avatars.AvatarDetailViewModel
import com.vrcx.android.ui.screen.avatars.AvatarsViewModel
import com.vrcx.android.ui.screen.avatars.MyAvatarsScreen
import com.vrcx.android.ui.screen.charts.ChartsScreen
import com.vrcx.android.ui.screen.charts.ChartsViewModel
import com.vrcx.android.ui.screen.dashboard.DashboardScreen
import com.vrcx.android.ui.screen.dashboard.DashboardViewModel
import com.vrcx.android.ui.screen.favorites.FavoritesScreen
import com.vrcx.android.ui.screen.favorites.FavoritesViewModel
import com.vrcx.android.ui.screen.feed.FeedScreen
import com.vrcx.android.ui.screen.feed.FeedViewModel
import com.vrcx.android.ui.screen.friendlog.FriendLogScreen
import com.vrcx.android.ui.screen.friendlog.FriendLogViewModel
import com.vrcx.android.ui.screen.friends.FriendsScreen
import com.vrcx.android.ui.screen.friends.FriendsViewModel
import com.vrcx.android.ui.screen.friendslocations.FriendsLocationsScreen
import com.vrcx.android.ui.screen.friendslocations.FriendsLocationsViewModel
import com.vrcx.android.ui.screen.gallery.GalleryScreen
import com.vrcx.android.ui.screen.gallery.GalleryViewModel
import com.vrcx.android.ui.screen.gamelog.GameLogScreen
import com.vrcx.android.ui.screen.gamelog.GameLogViewModel
import com.vrcx.android.ui.screen.groups.GroupDetailScreen
import com.vrcx.android.ui.screen.groups.GroupDetailViewModel
import com.vrcx.android.ui.screen.groups.GroupsScreen
import com.vrcx.android.ui.screen.groups.GroupsViewModel
import com.vrcx.android.ui.screen.login.LoginScreen
import com.vrcx.android.ui.screen.login.LoginViewModel
import com.vrcx.android.ui.screen.moderation.ModerationScreen
import com.vrcx.android.ui.screen.moderation.ModerationViewModel
import com.vrcx.android.ui.screen.notifications.NotificationsScreen
import com.vrcx.android.ui.screen.notifications.NotificationsViewModel
import com.vrcx.android.ui.screen.playerlist.PlayerListScreen
import com.vrcx.android.ui.screen.playerlist.PlayerListViewModel
import com.vrcx.android.ui.screen.profile.ProfileScreen
import com.vrcx.android.ui.screen.profile.ProfileViewModel
import com.vrcx.android.ui.screen.profile.UserDetailScreen
import com.vrcx.android.ui.screen.profile.UserDetailViewModel
import com.vrcx.android.ui.screen.search.SearchScreen
import com.vrcx.android.ui.screen.search.SearchViewModel
import com.vrcx.android.ui.screen.settings.CreditsScreen
import com.vrcx.android.ui.screen.settings.SettingsScreen
import com.vrcx.android.ui.screen.settings.SettingsViewModel
import com.vrcx.android.ui.screen.tools.ScreenshotMetadataScreen
import com.vrcx.android.ui.screen.tools.ScreenshotMetadataViewModel
import com.vrcx.android.ui.screen.tools.ToolsScreen
import com.vrcx.android.ui.screen.tools.ToolsViewModel
import com.vrcx.android.ui.screen.world.WorldDetailScreen
import com.vrcx.android.ui.screen.world.WorldDetailViewModel
import com.vrcx.android.ui.theme.VrcxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Renders every screen once against empty repository state. These do not assert on
 * content — they exist so that a crash inside a composable (a missing key, an
 * out-of-range index, a null dereference on absent data) fails the build instead of
 * shipping.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ScreenSmokeTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // Work the ViewModels queue on construction stays queued, so every screen renders
    // its initial state rather than whatever a half-finished refresh produces.
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun render(content: @Composable () -> Unit) {
        compose.setContent { VrcxTheme { content() } }
        compose.waitForIdle()
    }

    @Test
    fun `avatar detail screen composes`() = render {
        AvatarDetailScreen(
            AvatarDetailViewModel(SavedStateHandle(mapOf("avatarId" to "avtr_1")), fakeState(), fakeState())
        )
    }

    @Test
    fun `my avatars screen composes`() = render {
        MyAvatarsScreen(AvatarsViewModel(fakeState<AvatarRepository>()))
    }

    @Test
    fun `charts screen composes`() = render {
        ChartsScreen(ChartsViewModel(fakeState<FeedRepository>(), fakeState<AuthRepository>()))
    }

    @Test
    fun `credits screen composes`() {
        render { CreditsScreen() }

        // Canary for the harness itself: if this stops finding rendered text, the
        // other cases are passing without composing anything.
        compose.onNodeWithText("Credits").assertIsDisplayed()
    }

    @Test
    fun `dashboard screen composes`() = render {
        DashboardScreen(
            DashboardViewModel(fakeState<AuthRepository>(), fakeState<FriendRepository>(), fakeState<FeedRepository>())
        )
    }

    @Test
    fun `favorites screen composes`() = render {
        FavoritesScreen(
            FavoritesViewModel(fakeState<FavoriteRepository>(), fakeState<FriendRepository>(), fakeState<UserRepository>())
        )
    }

    @Test
    fun `feed screen composes`() = render {
        FeedScreen(
            FeedViewModel(
                fakeState<FeedRepository>(),
                fakeState<AuthRepository>(),
                fakeState<FriendRepository>(),
            )
        )
    }

    @Test
    fun `friend log screen composes`() = render {
        FriendLogScreen(FriendLogViewModel(fakeState<AuthRepository>(), fakeState<FriendRepository>()))
    }

    @Test
    fun `friends screen composes`() = render {
        FriendsScreen(FriendsViewModel(fakeState<FriendRepository>()))
    }

    @Test
    fun `friends locations screen composes`() = render {
        FriendsLocationsScreen(
            FriendsLocationsViewModel(
                fakeState<AuthRepository>(),
                fakeState<FriendRepository>(),
                fakeState<WorldRepository>(),
                fakeState<FeedRepository>(),
            )
        )
    }

    @Test
    fun `gallery screen composes`() = render {
        GalleryScreen(GalleryViewModel(fakeState<GalleryRepository>(), fakeState<AuthRepository>(), context))
    }

    @Test
    fun `game log screen composes`() = render {
        GameLogScreen(
            GameLogViewModel(
                fakeState<AuthRepository>(),
                fakeState<FeedRepository>(),
                fakeState<FriendRepository>(),
            )
        )
    }

    @Test
    fun `group detail screen composes`() = render {
        GroupDetailScreen(
            GroupDetailViewModel(SavedStateHandle(mapOf("groupId" to "grp_1")), fakeState())
        )
    }

    @Test
    fun `groups screen composes`() = render {
        GroupsScreen(GroupsViewModel(fakeState<GroupRepository>(), fakeState<AuthRepository>()))
    }

    @Test
    fun `login screen composes`() = render {
        LoginScreen(
            LoginViewModel(fakeState<AuthRepository>(), fakeState<VrcxPreferences>(), fakeState<SecureSecretsStore>())
        )
    }

    @Test
    fun `moderation screen composes`() = render {
        ModerationScreen(ModerationViewModel(fakeState<ModerationRepository>()))
    }

    @Test
    fun `notifications screen composes`() = render {
        NotificationsScreen(
            NotificationsViewModel(fakeState<NotificationRepository>(), fakeState<InviteMessageRepository>())
        )
    }

    @Test
    fun `player list screen composes`() = render {
        PlayerListScreen(PlayerListViewModel(fakeState<AuthRepository>(), fakeState<FriendRepository>()))
    }

    @Test
    fun `profile screen composes`() = render {
        ProfileScreen(ProfileViewModel(fakeState<AuthRepository>(), fakeState<UserRepository>()))
    }

    @Test
    fun `screenshot metadata screen composes`() = render {
        ScreenshotMetadataScreen(
            ScreenshotMetadataViewModel(fakeState<GalleryRepository>(), fakeState<AuthRepository>())
        )
    }

    @Test
    fun `search screen composes`() = render {
        SearchScreen(SearchViewModel(fakeState<SearchRepository>()))
    }

    @Test
    fun `settings screen composes`() = render {
        SettingsScreen(
            SettingsViewModel(
                fakeState<VrcxPreferences>(),
                fakeState<ProfilePicCacheManager>(),
                fakeState<FriendRepository>(),
                fakeState<AuthRepository>(),
            )
        )
    }

    @Test
    fun `tools screen composes`() = render {
        ToolsScreen(
            ToolsViewModel(
                fakeState<VrcxPreferences>(),
                fakeState<AuthRepository>(),
                fakeState<FriendRepository>(),
                fakeState<UserDetailRepository>(),
            )
        )
    }

    @Test
    fun `user detail screen composes`() = render {
        UserDetailScreen(
            UserDetailViewModel(
                SavedStateHandle(mapOf("userId" to "usr_1")),
                fakeState<UserDetailRepository>(),
                fakeState<UserActionPerformer>(),
            )
        )
    }

    @Test
    fun `world detail screen composes`() = render {
        WorldDetailScreen(
            WorldDetailViewModel(SavedStateHandle(mapOf("worldId" to "wrld_1")), fakeState())
        )
    }
}
