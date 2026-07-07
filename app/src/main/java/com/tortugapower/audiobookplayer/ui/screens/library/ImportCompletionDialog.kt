package com.tortugapower.audiobookplayer.ui.screens.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.logic.ImportCompletion
import com.tortugapower.audiobookplayer.viewmodel.LibraryViewModel

/**
 * Post-import placement prompt (BookPlayer iOS parity). Offers, per the imported batch:
 * - Library: leave items where they were inserted (default; also the dismiss action)
 * - Current folder: only when the user is inside a folder
 * - New folder: name input pre-filled with the batch's suggested name
 * - Existing folder: disabled when no other folders exist at the insertion level
 * - Create bound book: enabled when every imported item is a book (named volume from the books),
 *   or when exactly one folder was imported (converted in place, no name prompt)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportCompletionDialog(
    completion: ImportCompletion,
    currentFolderPath: String?,
    libraryViewModel: LibraryViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    val allBooks = completion.items.all { it.type == ItemType.BOOK }
    val singleFolder = completion.items.size == 1 && completion.items.first().type == ItemType.FOLDER
    val boundEnabled = allBooks || singleFolder

    // Folders available as a move destination at the insertion level, excluding just-imported ones.
    val importedUuids = remember(completion) { completion.items.map { it.uuid }.toSet() }
    val foldersAtBase by libraryViewModel.getFoldersForPath(completion.basePath).collectAsState()
    val destinationFolders = foldersAtBase.filter { it.uuid !in importedUuids }

    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showExistingFolderSheet by remember { mutableStateOf(false) }
    var showVolumeNameDialog by remember { mutableStateOf(false) }

    if (showNewFolderDialog) {
        NameInputDialog(
            title = stringResource(R.string.library_create_folder_title),
            label = stringResource(R.string.library_folder_name_label),
            initialValue = completion.suggestedName,
            existingNames = destinationFolders.map { it.title },
            onConfirm = { name ->
                libraryViewModel.createFolderAndMoveItems(context, name, completion.items, completion.basePath)
                onDismiss()
            },
            onDismiss = { showNewFolderDialog = false }
        )
        return
    }

    if (showVolumeNameDialog) {
        NameInputDialog(
            title = stringResource(R.string.library_combine_to_volume_title),
            label = stringResource(R.string.library_combine_to_volume_title),
            initialValue = completion.suggestedName,
            existingNames = destinationFolders.map { it.title },
            onConfirm = { name ->
                libraryViewModel.combineToVolume(context, completion.items, name)
                onDismiss()
            },
            onDismiss = { showVolumeNameDialog = false }
        )
        return
    }

    if (showExistingFolderSheet) {
        ModalBottomSheet(onDismissRequest = { showExistingFolderSheet = false }) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                Text(
                    text = stringResource(R.string.library_select_folder_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                LazyColumn {
                    items(destinationFolders) { folder ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    libraryViewModel.moveSelectedItems(context, completion.items, folder.relativePath)
                                    onDismiss()
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(folder.title, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
        return
    }

    AlertDialog(
        // Dismiss = "Library": the items stay where they were inserted.
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_complete_title)) },
        text = {
            Column {
                Text(stringResource(R.string.import_complete_message))
                Spacer(modifier = Modifier.height(8.dp))

                CompletionOption(text = stringResource(R.string.import_option_library), onClick = onDismiss)

                if (currentFolderPath != null) {
                    CompletionOption(
                        text = stringResource(R.string.import_option_current_folder),
                        onClick = {
                            libraryViewModel.moveSelectedItems(context, completion.items, currentFolderPath)
                            onDismiss()
                        }
                    )
                }

                CompletionOption(
                    text = stringResource(R.string.library_new_folder),
                    onClick = { showNewFolderDialog = true }
                )

                CompletionOption(
                    text = stringResource(R.string.library_existing_folder),
                    enabled = destinationFolders.isNotEmpty(),
                    onClick = { showExistingFolderSheet = true }
                )

                CompletionOption(
                    text = stringResource(R.string.library_combine_to_volume),
                    enabled = boundEnabled,
                    onClick = {
                        if (singleFolder) {
                            // A single imported folder converts to a bound book in place.
                            libraryViewModel.convertFoldersToVolumes(context, completion.items)
                            onDismiss()
                        } else {
                            showVolumeNameDialog = true
                        }
                    }
                )
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun CompletionOption(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun NameInputDialog(
    title: String,
    label: String,
    initialValue: String,
    existingNames: List<String>,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialValue) }
    val trimmed = name.trim()
    val duplicate = existingNames.any { it.equals(trimmed, ignoreCase = true) }
    val valid = trimmed.isNotEmpty() && !trimmed.contains('/') && !duplicate

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(label) },
                    singleLine = true,
                    isError = name.isNotEmpty() && !valid
                )
                if (duplicate) {
                    Text(
                        text = stringResource(R.string.library_folder_name_exists_error),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(trimmed) }, enabled = valid) {
                Text(stringResource(R.string.common_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}
