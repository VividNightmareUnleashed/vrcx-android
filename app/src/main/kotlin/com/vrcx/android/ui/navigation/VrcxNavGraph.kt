package com.vrcx.android.ui.navigation

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDeepLink
import androidx.navigation.NavGraphBuilder
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
import com.vrcx.android.ui.screen.tools.ScreenshotMetadataScreen
import com.vrcx.android.ui.screen.world.WorldDetailScreen
import java.nio.charset.StandardCharsets

object VrcxRoutes {
    const val FEED = "feed"
    const val FRIENDS = "friends"
    const val DASHBOARD = "dashboard"
    // These two are shown as "Activity History" and "Friends Roster". The route
    // strings are what saved navigation state resolves against, so they keep the
    // original names rather than following the labels.
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
    const val SCREENSHOT_METADATA = "screenshot_metadata"

    fun userDetail(userId: String) = "user_detail/${encodeRouteSegment(userId)}"
    fun groupDetail(groupId: String) = "group_detail/${encodeRouteSegment(groupId)}"
    fun avatarDetail(avatarId: String) = "avatar_detail/${encodeRouteSegment(avatarId)}"
    fun worldDetail(worldId: String) = "world_detail/${encodeRouteSegment(worldId)}"

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

/** A tab destination: crossfade in/out, no back-stack slide. */
private fun NavGraphBuilder.tabComposable(
    route: String,
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) = composable(
    route = route,
    enterTransition = { tabEnterTransition },
    exitTransition = { tabExitTransition },
    popEnterTransition = { tabEnterTransition },
    popExitTransition = { tabExitTransition },
    content = content,
)

/** A pushed sub-screen: slide in from the right, slide back out on pop. */
private fun NavGraphBuilder.subScreenComposable(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    deepLinks: List<NavDeepLink> = emptyList(),
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) = composable(
    route = route,
    arguments = arguments,
    deepLinks = deepLinks,
    enterTransition = { subScreenEnterTransition },
    exitTransition = { subScreenExitTransition },
    popEnterTransition = { subScreenPopEnterTransition },
    popExitTransition = { subScreenPopExitTransition },
    content = content,
)

@Composable
fun VrcxNavGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    val onBack: () -> Unit = { navController.popBackStack() }
    val onUser: (String) -> Unit = { userId -> navController.navigate(VrcxRoutes.userDetail(userId)) }
    val onWorld: (String) -> Unit = { worldId -> navController.navigate(VrcxRoutes.worldDetail(worldId)) }
    val onAvatar: (String) -> Unit = { avatarId -> navController.navigate(VrcxRoutes.avatarDetail(avatarId)) }
    val onGroup: (String) -> Unit = { groupId -> navController.navigate(VrcxRoutes.groupDetail(groupId)) }

    NavHost(
        navController = navController,
        startDestination = VrcxRoutes.FEED,
        modifier = modifier,
    ) {
        // Tab routes — crossfade
        tabComposable(VrcxRoutes.FEED) {
            FeedScreen(onUserClick = onUser)
        }
        tabComposable(VrcxRoutes.FRIENDS) {
            FriendsScreen(onFriendClick = onUser)
        }
        tabComposable(VrcxRoutes.SEARCH) {
            SearchScreen(
                onUserClick = onUser,
                onWorldClick = onWorld,
                onAvatarClick = onAvatar,
                onGroupClick = onGroup,
            )
        }
        tabComposable(VrcxRoutes.NOTIFICATIONS) {
            NotificationsScreen()
        }
        tabComposable(VrcxRoutes.PROFILE) {
            ProfileScreen(onNavigate = { route -> navController.navigate(route) })
        }

        // Sub-screen routes — slide
        subScreenComposable(VrcxRoutes.DASHBOARD) {
            DashboardScreen(
                onBack = onBack,
                onUserClick = onUser,
            )
        }
        subScreenComposable(VrcxRoutes.GAME_LOG) {
            GameLogScreen(
                onBack = onBack,
                onUserClick = onUser,
            )
        }
        subScreenComposable(VrcxRoutes.PLAYER_LIST) {
            PlayerListScreen(
                onBack = onBack,
                onUserClick = onUser,
            )
        }
        subScreenComposable(VrcxRoutes.TOOLS) {
            ToolsScreen(
                onBack = onBack,
                onOpenRoute = { navController.navigate(it) },
            )
        }
        subScreenComposable(VrcxRoutes.FAVORITES) {
            FavoritesScreen(
                onBack = onBack,
                onUserClick = onUser,
                onWorldClick = onWorld,
                onAvatarClick = onAvatar,
            )
        }
        subScreenComposable(VrcxRoutes.GROUPS) {
            GroupsScreen(
                onGroupClick = onGroup,
                onBack = onBack,
            )
        }
        subScreenComposable(VrcxRoutes.MY_AVATARS) {
            MyAvatarsScreen(
                onBack = onBack,
                onAvatarClick = onAvatar,
            )
        }
        subScreenComposable(VrcxRoutes.GALLERY) {
            GalleryScreen(onBack = onBack)
        }
        subScreenComposable(VrcxRoutes.SCREENSHOT_METADATA) {
            ScreenshotMetadataScreen(
                onBack = onBack,
                onUserClick = onUser,
                onWorldClick = onWorld,
            )
        }
        subScreenComposable(VrcxRoutes.CHARTS) {
            ChartsScreen(onBack = onBack)
        }
        subScreenComposable(VrcxRoutes.MODERATION) {
            ModerationScreen(onBack = onBack)
        }
        subScreenComposable(VrcxRoutes.SETTINGS) {
            SettingsScreen(
                onNavigateToCredits = { navController.navigate(VrcxRoutes.CREDITS) },
                onBack = onBack,
            )
        }
        subScreenComposable(VrcxRoutes.CREDITS) {
            CreditsScreen(onBack = onBack)
        }
        subScreenComposable(VrcxRoutes.FRIENDS_LOCATIONS) {
            FriendsLocationsScreen(
                onUserClick = onUser,
                onWorldClick = onWorld,
                onBack = onBack,
            )
        }
        subScreenComposable(VrcxRoutes.FRIEND_LOG) {
            FriendLogScreen(onBack = onBack)
        }
        subScreenComposable(
            VrcxRoutes.USER_DETAIL,
            arguments = listOf(navArgument("userId") { type = NavType.StringType }),
            deepLinks = vrchatDetailDeepLinks(DeepLinkSection.USER),
        ) {
            UserDetailScreen(
                onBack = onBack,
                onUserClick = onUser,
                onWorldClick = onWorld,
                onGroupClick = onGroup,
                onAvatarClick = onAvatar,
            )
        }
        subScreenComposable(
            VrcxRoutes.GROUP_DETAIL,
            arguments = listOf(navArgument("groupId") { type = NavType.StringType }),
            deepLinks = vrchatDetailDeepLinks(DeepLinkSection.GROUP),
        ) {
            GroupDetailScreen(
                onUserClick = onUser,
                onBack = onBack,
            )
        }
        subScreenComposable(
            VrcxRoutes.AVATAR_DETAIL,
            arguments = listOf(navArgument("avatarId") { type = NavType.StringType }),
            deepLinks = vrchatDetailDeepLinks(DeepLinkSection.AVATAR),
        ) {
            AvatarDetailScreen(
                onBack = onBack,
                onUserClick = onUser,
            )
        }
        subScreenComposable(
            VrcxRoutes.WORLD_DETAIL,
            arguments = listOf(navArgument("worldId") { type = NavType.StringType }),
            deepLinks = vrchatDetailDeepLinks(DeepLinkSection.WORLD),
        ) {
            WorldDetailScreen(
                onBack = onBack,
                onUserClick = onUser,
            )
        }
    }
}

/**
 * The detail screens reachable from outside the app.
 *
 * Three places have to agree on this list: the deep-link patterns below,
 * `MainActivity`'s URL normalizer, and the `pathPrefix` entries in
 * AndroidManifest. The first two read this enum; the manifest can't, so
 * `MainActivityTest` asserts the OS actually routes each section here.
 */
enum class DeepLinkSection(val segment: String, val argName: String) {
    USER("user", "userId"),
    WORLD("world", "worldId"),
    AVATAR("avatar", "avatarId"),
    GROUP("group", "groupId");

    /** `vrcx://user/usr_...` — the form the app's own notifications point at. */
    fun appUri(id: String): String = "$APP_SCHEME://$segment/${encodeRouteSegment(id)}"

    companion object {
        const val APP_SCHEME = "vrcx"
        const val WEB_HOST = "vrchat.com"

        fun fromSegment(segment: String): DeepLinkSection? =
            values().firstOrNull { it.segment == segment }
    }
}

/**
 * Build the deep-link list for a detail destination whose VRChat web URL
 * lives under `/home/{section}/`.
 *
 * `MainActivity.normalizeDeepLinkIntent` collapses deeper paths (e.g.
 * `/home/group/{id}/posts/{postId}/comments/{commentId}`) down to the
 * canonical single-segment form before NavController sees them, so we only
 * need to match the canonical shape here. The NavGraph stays simple
 * regardless of how deep VRChat's web URLs get.
 */
private fun vrchatDetailDeepLinks(section: DeepLinkSection): List<NavDeepLink> = listOf(
    navDeepLink { uriPattern = "${DeepLinkSection.APP_SCHEME}://${section.segment}/{${section.argName}}" },
    navDeepLink {
        uriPattern = "https://${DeepLinkSection.WEB_HOST}/home/${section.segment}/{${section.argName}}"
    },
)

internal fun encodeRouteSegment(value: String): String = buildString {
    value.toByteArray(StandardCharsets.UTF_8).forEach { byte ->
        val unsigned = byte.toInt() and 0xff
        val unreserved = unsigned in 'a'.code..'z'.code ||
            unsigned in 'A'.code..'Z'.code ||
            unsigned in '0'.code..'9'.code ||
            unsigned == '-'.code || unsigned == '.'.code || unsigned == '_'.code || unsigned == '~'.code
        if (unreserved) {
            append(unsigned.toChar())
        } else {
            append('%')
            append(HEX_DIGITS[unsigned ushr 4])
            append(HEX_DIGITS[unsigned and 0x0f])
        }
    }
}

private const val HEX_DIGITS = "0123456789ABCDEF"
