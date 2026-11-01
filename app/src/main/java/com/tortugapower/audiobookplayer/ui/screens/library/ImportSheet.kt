package com.tortugapower.audiobookplayer.ui.screens.library

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.viewmodel.ImportViewModel

@Composable
fun ImportSheet(viewModel: ImportViewModel, targetFolderPath: String? = null) {
    val context = LocalContext.current
    val files = viewModel.importedFiles
    // Keyed by position: two files can share a name.
    val rows = files.mapIndexed { index, file ->
        ImportConfirmationRow(
            id = index.toString(),
            icon = when {
                file.isStream -> Icons.Filled.Podcasts
                file.isDirectory -> Icons.Filled.Folder
                file.isFileOnly -> Icons.Filled.Description
                else -> Icons.Filled.AudioFile
            },
            title = file.name,
            tint = if (file.isFileOnly) MaterialTheme.colorScheme.secondary else null
        )
    }

    ImportConfirmationSheet(
        rows = rows,
        fileCount = files.size,
        onRemove = { row -> files.getOrNull(row.id.toInt())?.let { viewModel.removeFile(it) } },
        onConfirm = { viewModel.acceptImport(context, targetFolderPath) },
        onCancel = { viewModel.clearImport() },
        description = stringResource(R.string.import_disclaimer)
    ) {
        viewModel.processingFileName?.let { fileName ->
            Spacer(modifier = Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.import_processing_file, fileName),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    maxLines = 1
                )
            }
        }

        if (viewModel.skippedItemsCount > 0) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.import_skipped_items, viewModel.skippedItemsCount),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        if (viewModel.skippedNoAudioCount > 0) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.import_skipped_no_audio_files, viewModel.skippedNoAudioCount),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
    }
}
