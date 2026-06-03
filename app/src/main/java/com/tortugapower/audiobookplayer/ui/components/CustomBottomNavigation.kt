package com.tortugapower.audiobookplayer.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import com.tortugapower.audiobookplayer.R

sealed class Screen(
    val route: String,
    @StringRes val label: Int,
    val icon: @Composable () -> Unit,
) {
    open fun isSelected(currentRoute: String?): Boolean = currentRoute == route

    object Library : Screen(
        route = "library",
        label = R.string.library_title_default,
        icon = {
            Icon(
                painter = painterResource(R.drawable.ic_newsstand),
                contentDescription = stringResource(R.string.library_title_default),
            )
        },
    )

    object Profile : Screen(
        route = "profile",
        label = R.string.profile_title,
        icon = { Icon(Icons.Default.Person, contentDescription = stringResource(R.string.profile_title)) },
    ) {
        override fun isSelected(currentRoute: String?): Boolean {
            return currentRoute == route || 
                   currentRoute == "accountDetails" || 
                   currentRoute == "queuedTasks" || 
                   currentRoute?.startsWith("taskDetail/") == true
        }
    }

    object Settings : Screen(
        route = "settings",
        label = R.string.settings_title,
        icon = { Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings_title)) },
    ) {
        override fun isSelected(currentRoute: String?): Boolean {
            return currentRoute == route || currentRoute == "themes"
        }
    }
}

@Composable
fun CustomBottomNavigation(
    currentRoute: String?,
    onTabSelected: (Screen) -> Unit,
) {
    val screens = listOf(Screen.Library, Screen.Profile, Screen.Settings)
    ShortNavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        screens.forEach { screen ->
            ShortNavigationBarItem(
                selected = screen.isSelected(currentRoute),
                onClick = { onTabSelected(screen) },
                icon = screen.icon,
                label = { Text(stringResource(screen.label)) },
            )
        }
    }
}
