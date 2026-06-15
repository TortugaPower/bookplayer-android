package com.tortugapower.audiobookplayer.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.tortugapower.audiobookplayer.R

/**
 * Native Material alert dialog for auth errors — the Android equivalent of an iOS `.alert()`.
 * Renders only when [message] is non-null; [onDismiss] should clear the error state.
 */
@Composable
fun AuthErrorDialog(message: String?, onDismiss: () -> Unit) {
    if (message == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.common_error)) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_ok))
            }
        }
    )
}

