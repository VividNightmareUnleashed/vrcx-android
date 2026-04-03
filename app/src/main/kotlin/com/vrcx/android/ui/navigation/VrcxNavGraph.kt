package com.vrcx.android.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.vrcx.android.ui.screen.avatars.AvatarDetailScreen
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
import com.vrcx.android.ui.screen.settings.CreditsScreen
import com.vrcx.android.ui.screen.settings.SettingsScreen
import com.vrcx.android.ui.screen.world.WorldDetailScreen

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
            VrcxRoutes.FAVORITES,
            enterTransition = { subScreenEnterTransition },
            exitTransition = { subScreenExitTransition },
            popEnterTransition = { subScreenPopEnterTransition },
            popExitTransition = { subScreenPopExitTransition },
        ) {
            FavoritesScreen(onBack = onBack)
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

@Composable
private fun PlaceholderScreen(name: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(name) }
}
