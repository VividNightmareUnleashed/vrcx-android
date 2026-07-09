package com.vrcx.android.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavDeepLink
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.vrcx.android.ui.screen.avatars.AvatarDetailScreen
import com.vrcx.android.ui.screen.avatars.MyAvatarsScreen
import com.vrcx.android.ui.screen.charts.ChartsScreen
import com.vrcx.android.ui.screen.dashboard.DashboardScreen
import com.vrcx.android.ui.screen.favorites.FavoritesScreen
import com.vrcx.android.ui.screen.feed.FeedScreen
import com.vrcx.android.ui.screen.friendlog.FriendLogScreen
import com.vrcx.android.ui.screen.friends.FriendsScreen
import com.vrcx.android.ui.screen.friendslocations.FriendsLocationsScreen
import com.vrcx.android.ui.screen.gamelog.GameLogScreen
import com.vrcx.android.ui.screen.gallery.GalleryScreen
import com.vrcx.android.ui.screen.groups.GroupDetailScreen
import com.vrcx.android.ui.screen.groups.GroupsScreen
import com.vrcx.android.ui.screen.moderation.ModerationScreen
import com.vrcx.android.ui.screen.notifications.NotificationsScreen
import com.vrcx.android.ui.screen.playerlist.PlayerListScreen
import com.vrcx.android.ui.screen.profile.ProfileScreen
import com.vrcx.android.ui.screen.profile.UserDetailScreen
import com.vrcx.android.ui.screen.search.SearchScreen
import com.vrcx.android.ui.screen.settings.CreditsScreen
import com.vrcx.android.ui.screen.settings.SettingsScreen
import com.vrcx.android.ui.screen.tools.ToolsScreen
import com.vrcx.android.ui.screen.world.WorldDetailScreen

object VrcxRoutes {
    const val FEED = "feed"
    const val FRIENDS = "friends"
    const val DASHBOARD = "dashboard"
    const val GAME_LOG = "game_log"
    const val PLAYER_LIST = "player_list"
    const val TOOLS = "tools"
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
    const val WORLD_DETAIL = "world_detail/{worldId}"
    const val GALLERY = "gallery"
    const val CHARTS = "charts"
    const val MODERATION = "moderation"
    const val SETTINGS = "settings"
    const val CREDITS = "credits"

    fun userDetail(userId: String) = "user_detail/$userId"
    fun groupDetail(groupId: String) = "group_detail/$groupId"
    fun avatarDetail(avatarId: String) = "avatar_detail/$avatarId"
    fun worldDetail(worldId: String) = "world_detail/$worldId"

    val tabRoutes = setOf(FEED, FRIENDS, SEARCH, NOTIFICATIONS, PROFILE)
}

private const val FADE_DURATION = 300
private const val SLIDE_DURATION = 350

private val tabEnterTransition: EnterTransition = fadeIn(tween(FADE_DURATION))
private val tabExitTransition: ExitTransition = fadeOut(tween(FADE_DURATION))

private val subScreenEnterTransition: EnterTransition =
    slideInHorizontally(tween(SLIDE_DURATION)) { it } + fadeIn(tween(SLIDE_DURATION))
private val subScreenExitTransition: ExitTransition =
    fadeOut(tween(SLIDE_DURATION))
private val subScreenPopEnterTransition: EnterTransition =
    fadeIn(tween(SLIDE_DURATION))
private val subScreenPopExitTransition: ExitTransition =
    slideOutHorizontally(tween(SLIDE_DURATION)) { it } + fadeOut(tween(SLIDE_DURATION))

@Composable
fun VrcxNavGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    val onBack: () -> Unit = { navController.popBackStack() }

    NavHost(
        navController = navController,
        startDestination = VrcxRoutes.FEED,
        modifier = modifier,
    ) {
        // Tab routes — crossfade
        composable(
            VrcxRoutes.FEED,
            enterTransition = { tabEnterTransition },
            exitTransition = { tabExitTransition },
            popEnterTransition = { tabEnterTransition },
            popExitTransition = { tabExitTransition },
        ) {
            FeedScreen(onUserClick = { navController.navigate(VrcxRoutes.userDetail(it)) })
        }
        composable(
            VrcxRoutes.FRIENDS,
            enterTransition = { tabEnterTransition },
            exitTransition = { tabExitTransition },
            popEnterTransition = { tabEnterTransition },
            popExitTransition = { tabExitTransition },
        ) {
            FriendsScreen(onFriendClick = { navController.navigate(VrcxRoutes.userDetail(it)) })
        }
        composable(
            VrcxRoutes.SEARCH,
            enterTransition = { tabEnterTransition },
            exitTransition = { tabExitTransition },
            popEnterTransition = { tabEnterTransition },
            popExitTransition = { tabExitTransition },
        ) {
            SearchScreen(
                onUserClick = { navController.navigate(VrcxRoutes.userDetail(it)) },
                onWorldClick = { navController.navigate(VrcxRoutes.worldDetail(it)) },
                onAvatarClick = { navController.navigate(VrcxRoutes.avatarDetail(it)) },
                onGroupClick = { navController.navigate(VrcxRoutes.groupDetail(it)) },
            )
        }
        composable(
            VrcxRoutes.NOTIFICATIONS,
            enterTransition = { tabEnterTransition },
            exitTransition = { tabExitTransition },
            popEnterTransition = { tabEnterTransition },
            popExitTransition = { tabExitTransition },
        ) {
            NotificationsScreen()
        }
        composable(
            VrcxRoutes.PROFILE,
            enterTransition = { tabEnterTransition },
            exitTransition = { tabExitTransition },
            popEnterTransition = { tabEnterTransition },
            popExitTransition = { tabExitTransition },
        ) {
            ProfileScreen(onNavigate = { route -> navController.navigate(route) })
        }

        // Sub-screen routes — slide
        composable(
            VrcxRoutes.DASHBOARD,
            enterTransition = { subScreenEnterTransition },
            exitTransition = { subScreenExitTransition },
            popEnterTransition = { subScreenPopEnterTransition },
            popExitTransition = { subScreenPopExitTransition },
        ) {
            DashboardScreen(
                onBack = onBack,
                onUserClick = { navController.navigate(VrcxRoutes.userDetail(it)) },
            )
        }
        composable(
            VrcxRoutes.GAME_LOG,
            enterTransition = { subScreenEnterTransition },
            exitTransition = { subScreenExitTransition },
            popEnterTransition = { subScreenPopEnterTransition },
            popExitTransition = { subScreenPopExitTransition },
        ) {
            GameLogScreen(
                onBack = onBack,
                onUserClick = { navController.navigate(VrcxRoutes.userDetail(it)) },
            )
        }
        composable(
            VrcxRoutes.PLAYER_LIST,
            enterTransition = { subScreenEnterTransition },
            exitTransition = { subScreenExitTransition },
            popEnterTransition = { subScreenPopEnterTransition },
            popExitTransition = { subScreenPopExitTransition },
        ) {
            PlayerListScreen(
                onBack = onBack,
                onUserClick = { navController.navigate(VrcxRoutes.userDetail(it)) },
            )
        }
        composable(
            VrcxRoutes.TOOLS,
            enterTransition = { subScreenEnterTransition },
            exitTransition = { subScreenExitTransition },
            popEnterTransition = { subScreenPopEnterTransition },
            popExitTransition = { subScreenPopExitTransition },
        ) {
            ToolsScreen(
                onBack = onBack,
                onOpenRoute = { navController.navigate(it) },
            )
        }
        composable(
            VrcxRoutes.FAVORITES,
            enterTransition = { subScreenEnterTransition },
            exitTransition = { subScreenExitTransition },
            popEnterTransition = { subScreenPopEnterTransition },
            popExitTransition = { subScreenPopExitTransition },
        ) {
            FavoritesScreen(
                onBack = onBack,
                onUserClick = { navController.navigate(VrcxRoutes.userDetail(it)) },
                onWorldClick = { navController.navigate(VrcxRoutes.worldDetail(it)) },
                onAvatarClick = { navController.navigate(VrcxRoutes.avatarDetail(it)) },
            )
        }
        composable(
            VrcxRoutes.GROUPS,
            enterTransition = { subScreenEnterTransition },
            exitTransition = { subScreenExitTransition },
            popEnterTransition = { subScreenPopEnterTransition },
            popExitTransition = { subScreenPopExitTransition },
        ) {
            GroupsScreen(
                onGroupClick = { navController.navigate(VrcxRoutes.groupDetail(it)) },
                onBack = onBack,
            )
        }
        composable(
            VrcxRoutes.MY_AVATARS,
            enterTransition = { subScreenEnterTransition },
            exitTransition = { subScreenExitTransition },
            popEnterTransition = { subScreenPopEnterTransition },
            popExitTransition = { subScreenPopExitTransition },
        ) {
            MyAvatarsScreen(
                onBack = onBack,
                onAvatarClick = { navController.navigate(VrcxRoutes.avatarDetail(it)) },
            )
        }
        composable(
            VrcxRoutes.GALLERY,
            enterTransition = { subScreenEnterTransition },
            exitTransition = { subScreenExitTransition },
            popEnterTransition = { subScreenPopEnterTransition },
            popExitTransition = { subScreenPopExitTransition },
        ) {
            GalleryScreen(onBack = onBack)
        }
        composable(
            VrcxRoutes.CHARTS,
            enterTransition = { subScreenEnterTransition },
            exitTransition = { subScreenExitTransition },
            popEnterTransition = { subScreenPopEnterTransition },
            popExitTransition = { subScreenPopExitTransition },
        ) {
            ChartsScreen(onBack = onBack)
        }
        composable(
            VrcxRoutes.MODERATION,
            enterTransition = { subScreenEnterTransition },
            exitTransition = { subScreenExitTransition },
            popEnterTransition = { subScreenPopEnterTransition },
            popExitTransition = { subScreenPopExitTransition },
        ) {
            ModerationScreen(onBack = onBack)
        }
        composable(
            VrcxRoutes.SETTINGS,
            enterTransition = { subScreenEnterTransition },
            exitTransition = { subScreenExitTransition },
            popEnterTransition = { subScreenPopEnterTransition },
            popExitTransition = { subScreenPopExitTransition },
        ) {
            SettingsScreen(
                onNavigateToCredits = { navController.navigate(VrcxRoutes.CREDITS) },
                onBack = onBack,
            )
        }
        composable(
            VrcxRoutes.CREDITS,
            enterTransition = { subScreenEnterTransition },
            exitTransition = { subScreenExitTransition },
            popEnterTransition = { subScreenPopEnterTransition },
            popExitTransition = { subScreenPopExitTransition },
        ) {
            CreditsScreen(onBack = onBack)
        }
        composable(
            VrcxRoutes.FRIENDS_LOCATIONS,
            enterTransition = { subScreenEnterTransition },
            exitTransition = { subScreenExitTransition },
            popEnterTransition = { subScreenPopEnterTransition },
            popExitTransition = { subScreenPopExitTransition },
        ) {
            FriendsLocationsScreen(
                onUserClick = { navController.navigate(VrcxRoutes.userDetail(it)) },
                onWorldClick = { navController.navigate(VrcxRoutes.worldDetail(it)) },
                onBack = onBack,
            )
        }
        composable(
            VrcxRoutes.FRIEND_LOG,
            enterTransition = { subScreenEnterTransition },
            exitTransition = { subScreenExitTransition },
            popEnterTransition = { subScreenPopEnterTransition },
            popExitTransition = { subScreenPopExitTransition },
        ) {
            FriendLogScreen(onBack = onBack)
        }
        composable(
            VrcxRoutes.USER_DETAIL,
            arguments = listOf(navArgument("userId") { type = NavType.StringType }),
            deepLinks = vrchatDetailDeepLinks(section = "user", argName = "userId"),
            enterTransition = { subScreenEnterTransition },
            exitTransition = { subScreenExitTransition },
            popEnterTransition = { subScreenPopEnterTransition },
            popExitTransition = { subScreenPopExitTransition },
        ) {
            UserDetailScreen(
                onBack = onBack,
                onUserClick = { navController.navigate(VrcxRoutes.userDetail(it)) },
                onWorldClick = { navController.navigate(VrcxRoutes.worldDetail(it)) },
                onGroupClick = { navController.navigate(VrcxRoutes.groupDetail(it)) },
                onAvatarClick = { navController.navigate(VrcxRoutes.avatarDetail(it)) },
            )
        }
        composable(
            VrcxRoutes.GROUP_DETAIL,
            arguments = listOf(navArgument("groupId") { type = NavType.StringType }),
            deepLinks = vrchatDetailDeepLinks(section = "group", argName = "groupId"),
            enterTransition = { subScreenEnterTransition },
            exitTransition = { subScreenExitTransition },
            popEnterTransition = { subScreenPopEnterTransition },
            popExitTransition = { subScreenPopExitTransition },
        ) {
            GroupDetailScreen(
                onUserClick = { navController.navigate(VrcxRoutes.userDetail(it)) },
                onBack = onBack,
            )
        }
        composable(
            VrcxRoutes.AVATAR_DETAIL,
            arguments = listOf(navArgument("avatarId") { type = NavType.StringType }),
            deepLinks = vrchatDetailDeepLinks(section = "avatar", argName = "avatarId"),
            enterTransition = { subScreenEnterTransition },
            exitTransition = { subScreenExitTransition },
            popEnterTransition = { subScreenPopEnterTransition },
            popExitTransition = { subScreenPopExitTransition },
        ) {
            AvatarDetailScreen(
                onBack = onBack,
                onUserClick = { navController.navigate(VrcxRoutes.userDetail(it)) },
            )
        }
        composable(
            VrcxRoutes.WORLD_DETAIL,
            arguments = listOf(navArgument("worldId") { type = NavType.StringType }),
            deepLinks = vrchatDetailDeepLinks(section = "world", argName = "worldId"),
            enterTransition = { subScreenEnterTransition },
            exitTransition = { subScreenExitTransition },
            popEnterTransition = { subScreenPopEnterTransition },
            popExitTransition = { subScreenPopExitTransition },
        ) {
            WorldDetailScreen(
                onBack = onBack,
                onUserClick = { navController.navigate(VrcxRoutes.userDetail(it)) },
            )
        }
    }
}

/**
 * Build the deep-link list for a detail destination whose VRChat web URL
 * lives under `/home/{section}/` and whose primary argument is `{$argName}`.
 *
 * `MainActivity.normalizeDeepLinkIntent` collapses deeper paths (e.g.
 * `/home/group/{id}/posts/{postId}/comments/{commentId}`) down to the
 * canonical single-segment form before NavController sees them, so we only
 * need to match the canonical shape here. The NavGraph stays simple
 * regardless of how deep VRChat's web URLs get.
 */
private fun vrchatDetailDeepLinks(section: String, argName: String): List<NavDeepLink> = listOf(
    navDeepLink { uriPattern = "vrcx://$section/{$argName}" },
    navDeepLink { uriPattern = "https://vrchat.com/home/$section/{$argName}" },
)
