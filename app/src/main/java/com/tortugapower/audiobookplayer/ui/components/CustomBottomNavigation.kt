package com.tortugapower.audiobookplayer.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import com.tortugapower.audiobookplayer.R

sealed class Screen(
    val route: String,
    val label: String,
    val icon: @Composable () -> Unit,
) {
    object Library : Screen(
        route = "library",
        label = "Library",
        icon = {
            Icon(
                painter = painterResource(R.drawable.ic_newsstand),
                contentDescription = "Library",
            )
        },
    )
    object Profile : Screen(
        route = "profile",
        label = "Profile",
        icon = { Icon(Icons.Default.Person, contentDescription = "Profile") },
    )
    object Settings : Screen(
        route = "settings",
        label = "Settings",
        icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
    )
}

@Composable
fun CustomBottomNavigation(
    currentRoute: String?,
    onTabSelected: (Screen) -> Unit,
) {
    val screens = listOf(Screen.Library, Screen.Settings)
    ShortNavigationBar {
        screens.forEach { screen ->
            ShortNavigationBarItem(
                selected = currentRoute == screen.route,
                onClick = { onTabSelected(screen) },
                icon = screen.icon,
                label = { Text(screen.label) },
            )
        }
    }
}
