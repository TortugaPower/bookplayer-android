package com.tortugapower.audiobookplayer.ui.components

import android.content.Context
import android.content.Intent
import android.os.storage.StorageManager
import android.provider.Settings
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.logic.StorageMonitor

/**
 * The three faces of a full disk ([StorageMonitor]): a full-screen gate at launch when the database
 * can't be trusted to open, a banner while the app runs with storage critical or a transfer waiting
 * for space, and the explanation for a refused/stopped playback. All offer the system's own
 * "free up space" screen; none of them touch the database.
 */

/** Opens the system storage-management UI (the same "Free up space" screen Files/Settings use). */
fun openStorageSettings(context: Context) {
    // Most specific first; ACTION_SETTINGS resolves on every device, so the button is never a no-op.
    val candidates = listOf(
        Intent(StorageManager.ACTION_MANAGE_STORAGE),
        Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
    for (intent in candidates) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: Exception) {
            // try the next one
        }
    }
}

@Composable
private fun freeSpaceLabel(state: StorageMonitor.State): String {
    val bytes = if (state.availableBytes == Long.MAX_VALUE) 0L else state.availableBytes.coerceAtLeast(0L)
    return Formatter.formatFileSize(LocalContext.current, bytes)
}

@Composable
fun StorageFullScreen(state: StorageMonitor.State, onRetry: () -> Unit, onFreeUpSpace: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null, // decorative: the title carries the meaning
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.storage_full_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.storage_full_message, freeSpaceLabel(state)),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onFreeUpSpace) { Text(stringResource(R.string.storage_full_free_up)) }
            TextButton(onClick = onRetry) { Text(stringResource(R.string.storage_full_retry)) }
        }
    }
}

@Composable
fun StorageFullBanner(
    state: StorageMonitor.State,
    onFreeUpSpace: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val critical = state.isCritical
    val container = if (critical) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer
    val content = if (critical) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.medium,
        color = container,
        contentColor = content,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = Icons.Filled.Warning, contentDescription = null, tint = content)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(if (critical) R.string.storage_full_banner else R.string.storage_low_banner),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row {
                    TextButton(onClick = onFreeUpSpace) { Text(stringResource(R.string.storage_full_free_up)) }
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.storage_full_retry)) }
                }
            }
        }
    }
}

@Composable
fun StorageFullDialog(state: StorageMonitor.State, onDismiss: () -> Unit, onFreeUpSpace: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.storage_full_title)) },
        text = { Text(stringResource(R.string.storage_full_playback_blocked, freeSpaceLabel(state))) },
        confirmButton = {
            TextButton(onClick = { onFreeUpSpace(); onDismiss() }) { Text(stringResource(R.string.storage_full_free_up)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.storage_full_dismiss)) }
        },
    )
}
