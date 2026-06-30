package com.tortugapower.audiobookplayer.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.logic.ImportFile
import com.tortugapower.audiobookplayer.viewmodel.ImportViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportSheet(viewModel: ImportViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    ModalBottomSheet(
        onDismissRequest = { viewModel.showImportSheet = false },
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null,
        modifier = Modifier.fillMaxHeight(0.9f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { viewModel.clearImport() },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                ) {
                    Icon(
                        Icons.Default.Close, 
                        contentDescription = stringResource(R.string.common_close), 
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }

                IconButton(
                    onClick = { viewModel.acceptImport(context) },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                ) {
                    Icon(
                        Icons.Default.Check, 
                        contentDescription = stringResource(R.string.common_accept), 
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.import_title),
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.import_disclaimer),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 16.sp,
                lineHeight = 22.sp
            )

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = stringResource(R.string.import_files_count, viewModel.importedFiles.size),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(viewModel.importedFiles) { file ->
                    ImportListItem(file, onRemove = { viewModel.removeFile(file) })
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant, 
                        thickness = 0.5.dp
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
        }
    }
}

@Composable
fun ImportListItem(file: ImportFile, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.RemoveCircle,
            contentDescription = stringResource(R.string.common_remove),
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .size(28.dp)
                .clickable { onRemove() }
        )

        Spacer(modifier = Modifier.width(16.dp))

        Icon(
            imageVector = if (file.isFileOnly) Icons.Filled.Description else Icons.Filled.AudioFile,
            contentDescription = null,
            tint = if (file.isFileOnly) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Text(
            text = file.name,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp,
            maxLines = 1
        )
    }
}
