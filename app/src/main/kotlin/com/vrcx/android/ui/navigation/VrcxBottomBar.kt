package com.vrcx.android.ui.navigation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DynamicFeed
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.DynamicFeed
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.vrcx.android.ui.theme.LocalWallpaperActive
import com.vrcx.android.ui.theme.vrcxColors

data class BottomNavItem(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

val bottomNavItems = listOf(
    BottomNavItem(VrcxRoutes.FEED, "Feed", Icons.Filled.DynamicFeed, Icons.Outlined.DynamicFeed),
    BottomNavItem(VrcxRoutes.FRIENDS, "Friends", Icons.Filled.Group, Icons.Outlined.Group),
    BottomNavItem(VrcxRoutes.SEARCH, "Search", Icons.Filled.Search, Icons.Outlined.Search),
    BottomNavItem(VrcxRoutes.NOTIFICATIONS, "Alerts", Icons.Filled.Notifications, Icons.Outlined.Notifications),
    BottomNavItem(VrcxRoutes.PROFILE, "More", Icons.Filled.Menu, Icons.Outlined.Menu),
)

@Composable
fun VrcxBottomBar(navController: NavController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    if (currentRoute !in VrcxRoutes.tabRoutes) return

    val isWallpaperActive = LocalWallpaperActive.current
    val vrcxColors = MaterialTheme.vrcxColors

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        shape = MaterialTheme.shapes.medium,
        color = vrcxColors.panelBackground.let {
            if (isWallpaperActive) it.copy(alpha = 0.9f) else it
        },
        border = BorderStroke(1.dp, vrcxColors.panelBorder),
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            bottomNavItems.forEach { item ->
                val selected = currentRoute == item.route
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            color = if (selected) vrcxColors.navActive else Color.Transparent,
                            shape = MaterialTheme.shapes.small,
                        )
                        .clickable {
                            if (currentRoute != item.route) {
                                navController.navigate(item.route) {
                                    popUpTo(VrcxRoutes.FEED) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        }
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        modifier = Modifier.size(28.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                            contentDescription = item.label,
                            tint = if (selected) vrcxColors.navActiveContent else vrcxColors.navInactiveContent,
                        )
                    }
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) vrcxColors.navActiveContent else vrcxColors.navInactiveContent,
                    )
                }
            }
        }
    }
}
