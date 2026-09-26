package com.tortugapower.audiobookplayer.ui.screens.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.tortugapower.audiobookplayer.R

/**
 * iOS's `import_no_audio_files_alert`: nothing in the selected media-server items had audio-file
 * metadata, so nothing was staged — an extension is never guessed to make them importable.
 */
@Composable
fun NoAudioFilesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.import_title)) },
        text = { Text(stringResource(id = R.string.import_no_audio_files_alert)) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(id = R.string.common_ok)) }
        }
    )
}
