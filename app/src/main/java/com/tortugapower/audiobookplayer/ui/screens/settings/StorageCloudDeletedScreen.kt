package com.tortugapower.audiobookplayer.ui.screens.settings

import android.content.Context
import android.media.MediaMetadataRetriever
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.logic.ArtworkManager
import com.tortugapower.audiobookplayer.logic.ImportManager
import com.tortugapower.audiobookplayer.repository.LibraryRepository
import com.tortugapower.audiobookplayer.repository.RoomLibraryRepository
import com.tortugapower.audiobookplayer.ui.components.BookPlayerTabScaffold
import com.tortugapower.audiobookplayer.ui.components.LocalMiniPlayerInset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageCloudDeletedScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val database = remember { AppDatabase.getDatabase(context) }
    val repository = remember { RoomLibraryRepository(context.applicationContext, database.libraryDao()) }

    val backupDir = remember { File(context.filesDir, "BPBackup") }

    var sortBy by remember { mutableStateOf(SortType.SIZE) }
    var showSortMenu by remember { mutableStateOf(false) }
    var fileToDelete by remember { mutableStateOf<File?>(null) }
    var showRestoreAllConfirm by remember { mutableStateOf(false) }

    // Trigger state to force reload of files from disk
    var reloadTrigger by remember { mutableIntStateOf(0) }

    val activeImportFiles = remember {
        ImportManager.importedFiles.map { it.file.absolutePath }.toSet()
    }

    val backupFiles = remember(reloadTrigger) {
        if (backupDir.exists()) {
            backupDir.listFiles()?.filter { it.isFile && !activeImportFiles.contains(it.absolutePath) } ?: emptyList()
        } else emptyList()
    }

    val totalSpace = remember(backupFiles) {
        backupFiles.sumOf { it.length() }
    }

    val totalSpaceStr = remember(totalSpace) { Formatter.formatShortFileSize(context, totalSpace) }

    val sortedFiles = remember(backupFiles, sortBy) {
        when (sortBy) {
            SortType.SIZE -> backupFiles.sortedByDescending { it.length() }
            SortType.NAME -> backupFiles.sortedBy { it.name }
        }
    }

    if (fileToDelete != null) {
        AlertDialog(
            onDismissRequest = { fileToDelete = null },
            title = { Text(stringResource(R.string.storage_cloud_deleted_delete_confirm_title)) },
            text = { Text(stringResource(R.string.storage_cloud_deleted_delete_confirm_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val file = fileToDelete
                        if (file != null && file.exists()) {
                            file.delete()
                            reloadTrigger++
                            fileToDelete = null
                        }
                    }
                ) {
                    Text(
                        text = stringResource(R.string.storage_cloud_deleted_delete_button),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { fileToDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    if (showRestoreAllConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreAllConfirm = false },
            title = { Text(stringResource(R.string.storage_cloud_deleted_restore_all_confirm_title)) },
            text = { Text(stringResource(R.string.storage_cloud_deleted_restore_all_confirm_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                backupFiles.forEach { file ->
                                    restoreBackupFile(context, file, repository)
                                }
                            }
                            reloadTrigger++
                            showRestoreAllConfirm = false
                        }
                    }
                ) {
                    Text(
                        text = stringResource(R.string.storage_cloud_deleted_restore_all),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreAllConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    BookPlayerTabScaffold(
        title = stringResource(R.string.storage_cloud_deleted_title),
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.common_back),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        actions = {
            if (backupFiles.isNotEmpty()) {
                TextButton(onClick = { showRestoreAllConfirm = true }) {
                    Text(
                        text = stringResource(R.string.storage_cloud_deleted_restore_all),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 16.sp
                    )
                }
            }

            Box {
                TextButton(onClick = { showSortMenu = true }) {
                    Text(
                        text = stringResource(R.string.storage_management_sort_label),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 16.sp
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "Sort Menu",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                DropdownMenu(
                    expanded = showSortMenu,
                    onDismissRequest = { showSortMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.storage_management_sort_by_size)) },
                        onClick = {
                            sortBy = SortType.SIZE
                            showSortMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.storage_management_sort_by_name)) },
                        onClick = {
                            sortBy = SortType.NAME
                            showSortMenu = false
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 24.dp),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 16.dp,
                bottom = innerPadding.calculateBottomPadding() + 16.dp + LocalMiniPlayerInset.current
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.storage_cloud_deleted_total_size),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = totalSpaceStr,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                val count = sortedFiles.size
                val label = if (count == 1) {
                    stringResource(R.string.storage_management_file_count_single, count)
                } else {
                    stringResource(R.string.storage_management_file_count_multiple, count)
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            items(sortedFiles, key = { it.absolutePath }) { file ->
                val sizeStr = remember(file) { Formatter.formatShortFileSize(context, file.length()) }
                StorageBackupFileListItem(
                    fileName = file.name,
                    sizeStr = sizeStr,
                    onDeleteClick = { fileToDelete = file },
                    onRestoreClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                restoreBackupFile(context, file, repository)
                            }
                            reloadTrigger++
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun StorageBackupFileListItem(
    fileName: String,
    sizeStr: String,
    onDeleteClick: () -> Unit,
    onRestoreClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.RemoveCircle,
                contentDescription = "Delete permanently",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .size(28.dp)
                    .clickable { onDeleteClick() }
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fileName.substringBeforeLast('.'),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = sizeStr,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            TextButton(onClick = onRestoreClick) {
                Text(
                    text = stringResource(R.string.storage_cloud_deleted_restore_button),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

private fun getDuration(file: File): Double {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        val time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        time?.toDoubleOrNull()?.div(1000.0) ?: 0.0
    } catch (e: Exception) {
        0.0
    } finally {
        retriever.release()
    }
}

suspend fun restoreBackupFile(context: Context, file: File, repository: LibraryRepository) {
    withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext
        
        val processedDir = File(context.filesDir, "Processed")
        if (!processedDir.exists()) processedDir.mkdirs()
        
        val duration = getDuration(file)
        
        val artworkDir = File(context.filesDir, "Artworks")
        if (!artworkDir.exists()) artworkDir.mkdirs()
        val artworkFile = File(artworkDir, "${java.util.UUID.randomUUID()}.jpg")
        val hasArtwork = ArtworkManager.extractAndSaveArtwork(file, artworkFile)
        
        val destinationFile = File(processedDir, file.name)
        file.renameTo(destinationFile)
        
        val database = AppDatabase.getDatabase(context)
        val libraryDao = database.libraryDao()
        val currentMaxRank = libraryDao.getMaxRootOrderRank() ?: -1
        
        val entity = LibraryItemEntity(
            uuid = java.util.UUID.randomUUID().toString(),
            title = file.name.substringBeforeLast('.'),
            originalFileName = file.name,
            relativePath = file.name,
            type = com.tortugapower.audiobookplayer.database.entities.ItemType.BOOK,
            duration = duration,
            artworkURL = if (hasArtwork) artworkFile.absolutePath else null,
            orderRank = currentMaxRank + 1
        )
        libraryDao.insertItem(entity)
    }
}
