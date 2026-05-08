package com.tortugapower.audiobookplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Library : Screen("library", "Library", Icons.Outlined.LibraryBooks)
    object Profile : Screen("profile", "Profile", Icons.Default.Person)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
}

@Composable
fun CustomBottomNavigation(
    currentRoute: String?,
    onTabSelected: (Screen) -> Unit
) {
    val screens = listOf(Screen.Library, Screen.Profile, Screen.Settings)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .height(72.dp)
            .background(Color.Transparent),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Tabs Container
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(36.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            screens.forEach { screen ->
                val isSelected = currentRoute == screen.route
                BottomNavItem(
                    screen = screen,
                    isSelected = isSelected,
                    onClick = { onTabSelected(screen) }
                )
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Search Action
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(MaterialTheme.colorScheme.surface)
                .clickable { /* Handle Search */ },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Search",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
fun BottomNavItem(
    screen: Screen,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Transparent
    val contentColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(36.dp))
            .background(backgroundColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = screen.icon,
                contentDescription = screen.label,
                tint = contentColor,
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = screen.label,
                color = contentColor,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}
