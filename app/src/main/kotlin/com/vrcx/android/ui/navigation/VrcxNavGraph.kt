package com.vrcx.android.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.vrcx.android.ui.screen.avatars.MyAvatarsScreen
import com.vrcx.android.ui.screen.charts.ChartsScreen
import com.vrcx.android.ui.screen.favorites.FavoritesScreen
import com.vrcx.android.ui.screen.feed.FeedScreen
import com.vrcx.android.ui.screen.friendlog.FriendLogScreen
import com.vrcx.android.ui.screen.friends.FriendsScreen
import com.vrcx.android.ui.screen.friendslocations.FriendsLocationsScreen
import com.vrcx.android.ui.screen.gallery.GalleryScreen
import com.vrcx.android.ui.screen.groups.GroupDetailScreen
import com.vrcx.android.ui.screen.groups.GroupsScreen
import com.vrcx.android.ui.screen.moderation.ModerationScreen
import com.vrcx.android.ui.screen.notifications.NotificationsScreen
import com.vrcx.android.ui.screen.profile.ProfileScreen
import com.vrcx.android.ui.screen.profile.UserDetailScreen
import com.vrcx.android.ui.screen.search.SearchScreen
import com.vrcx.android.ui.screen.settings.SettingsScreen

object VrcxRoutes {
    const val FEED = "feed"
    const val FRIENDS = "friends"
    const val FRIENDS_LOCATIONS = "friends_locations"
    const val FRIEND_LOG = "friend_log"
    const val SEARCH = "search"
    const val FAVORITES = "favorites"
    const val GROUPS = "groups"
    const val GROUP_DETAIL = "group_detail/{groupId}"
    const val NOTIFICATIONS = "notifications"
    const val PROFILE = "profile"
    const val USER_DETAIL = "user_detail/{userId}"
    const val MY_AVATARS = "my_avatars"
    const val AVATAR_DETAIL = "avatar_detail/{avatarId}"
    const val GALLERY = "gallery"
    const val CHARTS = "charts"
    const val MODERATION = "moderation"
    const val SETTINGS = "settings"

    fun userDetail(userId: String) = "user_detail/$userId"
    fun groupDetail(groupId: String) = "group_detail/$groupId"
    fun avatarDetail(avatarId: String) = "avatar_detail/$avatarId"
}

@Composable
fun VrcxNavGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = VrcxRoutes.FEED,
        modifier = modifier,
    ) {
        composable(VrcxRoutes.FEED) {
            FeedScreen(onUserClick = { navController.navigate(VrcxRoutes.userDetail(it)) })
        }
        composable(VrcxRoutes.FRIENDS) {
            FriendsScreen(onFriendClick = { navController.navigate(VrcxRoutes.userDetail(it)) })
        }
        composable(VrcxRoutes.SEARCH) {
            SearchScreen(onUserClick = { navController.navigate(VrcxRoutes.userDetail(it)) })
        }
        composable(VrcxRoutes.NOTIFICATIONS) { NotificationsScreen() }
        composable(VrcxRoutes.PROFILE) {
            ProfileScreen(onNavigate = { route -> navController.navigate(route) })
        }
        composable(VrcxRoutes.FAVORITES) { FavoritesScreen() }
        composable(VrcxRoutes.GROUPS) {
            GroupsScreen(onGroupClick = { navController.navigate(VrcxRoutes.groupDetail(it)) })
        }
        composable(VrcxRoutes.MY_AVATARS) { MyAvatarsScreen() }
        composable(VrcxRoutes.GALLERY) { GalleryScreen() }
        composable(VrcxRoutes.CHARTS) { ChartsScreen() }
        composable(VrcxRoutes.MODERATION) { ModerationScreen() }
        composable(VrcxRoutes.SETTINGS) { SettingsScreen() }
        composable(VrcxRoutes.FRIENDS_LOCATIONS) { FriendsLocationsScreen() }
        composable(VrcxRoutes.FRIEND_LOG) { FriendLogScreen() }
        composable(VrcxRoutes.USER_DETAIL) { UserDetailScreen() }
        composable(VrcxRoutes.GROUP_DETAIL) { GroupDetailScreen() }
        composable(VrcxRoutes.AVATAR_DETAIL) { PlaceholderScreen("Avatar Detail") }
    }
}

@Composable
private fun PlaceholderScreen(name: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(name) }
}
