package com.tortugapower.audiobookplayer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * A tappable settings row showing a [label] on the leading edge and an optional read-only [value]
 * on the trailing edge (e.g. "Theme … Default / Dark"). Pass `value = null` for an action row with
 * no trailing text (e.g. Support links). Shared list-row primitive used by the Settings and Themes
 * screens — lives in `ui.components` so neither feature package owns the other's widget.
 *
 * @param label the row title
 * @param value the trailing read-only value, or null for a label-only action row
 * @param onClick invoked when the row is tapped
 */
@Composable
fun SettingsItem(label: String, value: String? = null, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = value?.let {
            { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/**
 * A settings row with a trailing [Switch]. Tapping anywhere on the row toggles the switch. When
 * [enabled] is false the row is dimmed and non-interactive. Shared list-row primitive used by the
 * Settings and Themes screens.
 *
 * @param label the row title
 * @param checked current switch state
 * @param onCheckedChange invoked with the new state when toggled
 * @param enabled whether the row is interactive (dimmed when false)
 */
@Composable
fun SettingsToggleItem(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    ListItem(
        headlineContent = {
            val color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            Text(label, color = color)
        },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(enabled = enabled) { onCheckedChange(!checked) },
    )
}
