package com.tortugapower.audiobookplayer.ui.screens.settings

import com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.RemoveCircle
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
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.logic.PlaybackManager
import com.tortugapower.audiobookplayer.logic.hasQueuedUploadTask
import com.tortugapower.audiobookplayer.logic.removeLocalFile
import com.tortugapower.audiobookplayer.repository.LibraryRepository
import com.tortugapower.audiobookplayer.repository.RoomLibraryRepository
import com.tortugapower.audiobookplayer.ui.components.BookPlayerTabScaffold
import com.tortugapower.audiobookplayer.ui.components.LocalMiniPlayerInset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class SortType {
    NAME, SIZE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageManagementScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    val database = remember { AppDatabase.getDatabase(context) }
    val repository = remember { RoomLibraryRepository(context.applicationContext, database.libraryDao()) }
    val syncTaskRepository = remember { com.tortugapower.audiobookplayer.repository.RoomSyncTaskRepository(database.syncTaskDao()) }

    val booksFlow = remember { repository.searchBooks("") }
    val books by booksFlow.collectAsState(initial = emptyList())

    val processedDir = remember { File(context.filesDir, "Processed") }
    val artworkDir = remember { File(context.filesDir, "Artworks") }

    var sortBy by remember { mutableStateOf(SortType.SIZE) }
    var showSortMenu by remember { mutableStateOf(false) }
    var itemToDelete by remember { mutableStateOf<LibraryItemEntity?>(null) }
    // iOS parity (StorageViewModel.checkAndDeleteSelectedItem): a file with a queued upload task
    // gets an extra warning — removing it means the app can never upload it.
    var uploadWarningItem by remember { mutableStateOf<LibraryItemEntity?>(null) }

    // Directory walks and per-file stat calls are disk IO — computed off the main thread and
    // re-run whenever the library flow emits (removals update the DB, which re-triggers this).
    var stats by remember { mutableStateOf(StorageStats()) }
    LaunchedEffect(books) {
        stats = withContext(Dispatchers.IO) {
            val localBooks = books.mapNotNull { book ->
                val path = book.relativePath
                if (!path.isNullOrEmpty()) {
                    val file = File(processedDir, path)
                    if (file.exists() && file.isFile) {
                        book to file
                    } else null
                } else null
            }
            StorageStats(
                localBooks = localBooks,
                sizesByUuid = localBooks.associate { it.first.uuid to it.second.length() },
                totalSpace = if (processedDir.exists()) {
                    processedDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
                } else 0L,
                artworkSpace = if (artworkDir.exists()) {
                    artworkDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
                } else 0L
            )
        }
    }

    val totalSpaceStr = remember(stats) { Formatter.formatShortFileSize(context, stats.totalSpace) }
    val artworkSpaceStr = remember(stats) { Formatter.formatShortFileSize(context, stats.artworkSpace) }

    val sortedBooks = remember(stats, sortBy) {
        when (sortBy) {
            SortType.SIZE -> stats.localBooks.sortedByDescending { stats.sizesByUuid[it.first.uuid] ?: 0L }
            SortType.NAME -> stats.localBooks.sortedBy { it.first.title }
        }
    }

    if (itemToDelete != null) {
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            title = { Text(stringResource(R.string.storage_management_remove_confirm_title)) },
            text = { Text(stringResource(R.string.storage_management_remove_confirm_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val item = itemToDelete
                        if (item != null) {
                            scope.launch {
                                // iOS parity: a pending/running upload for this book means the file
                                // hasn't reached the cloud — escalate to the upload warning instead
                                // of silently destroying the only copy.
                                val hasUploadTask = hasQueuedUploadTask(syncTaskRepository, repository, item)
                                itemToDelete = null
                                if (hasUploadTask) {
                                    uploadWarningItem = item
                                } else {
                                    removeLocalFile(context, repository, item)
                                }
                            }
                        }
                    }
                ) {
                    Text(
                        text = stringResource(R.string.storage_management_remove_button),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    // iOS's uploadTaskAlert: Warning + "queued upload task for <title>" + destructive Remove.
    uploadWarningItem?.let { item ->
        AlertDialog(
            onDismissRequest = { uploadWarningItem = null },
            title = { Text(stringResource(R.string.storage_management_warning_title)) },
            text = { Text(stringResource(R.string.storage_management_upload_queued_warning, item.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            removeLocalFile(context, repository, item)
                            uploadWarningItem = null
                        }
                    }
                ) {
                    Text(
                        text = stringResource(R.string.storage_management_remove_button),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { uploadWarningItem = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    BookPlayerTabScaffold(
        title = stringResource(R.string.storage_management_title),
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
            Box {
                TextButton(
                    onClick = { showSortMenu = true }
                ) {
                    Text(
                        text = stringResource(R.string.storage_management_sort_label),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 16.sp
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        // Decorative: the enclosing TextButton already reads its "Sort" label.
                        contentDescription = null,
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
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.storage_management_total_space),
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
                        
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 12.dp),
                            color = MaterialTheme.outlineVariantColor(),
                            thickness = 0.5.dp
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.storage_management_artwork_cache),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = artworkSpaceStr,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item {
                val count = sortedBooks.size
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

            if (sortedBooks.isNotEmpty()) {
                // One lazy slot per row (keyed) so large libraries stay virtualized — a single
                // item{} wrapping every row composes them all eagerly. The continuous-card look is
                // kept by rounding only the first/last rows and drawing dividers between.
                itemsIndexed(sortedBooks, key = { _, (book, _) -> book.uuid }) { index, (book, file) ->
                    val shape = when {
                        sortedBooks.size == 1 -> RoundedCornerShape(12.dp)
                        index == 0 -> RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
                        index == sortedBooks.size - 1 -> RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
                        else -> RoundedCornerShape(0.dp)
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    ) {
                        val sizeStr = remember(stats, book.uuid) {
                            Formatter.formatShortFileSize(context, stats.sizesByUuid[book.uuid] ?: 0L)
                        }
                        StorageFileListItem(
                            title = book.title,
                            fileName = book.originalFileName ?: file.name,
                            sizeStr = sizeStr,
                            onRemoveClick = { itemToDelete = book }
                        )
                        if (index < sortedBooks.size - 1) {
                            HorizontalDivider(
                                color = MaterialTheme.outlineVariantColor(),
                                thickness = 0.5.dp,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StorageFileListItem(
    title: String,
    fileName: String,
    sizeStr: String,
    onRemoveClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onRemoveClick) {
            Icon(
                imageVector = Icons.Default.RemoveCircle,
                contentDescription = stringResource(R.string.storage_management_remove_button),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(28.dp)
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
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
    }
}

@Composable
private fun MaterialTheme.outlineVariantColor(): Color {
    return colorScheme.onSurface.copy(alpha = 0.08f)
}

/** Disk-derived screen state, computed off the main thread (see the LaunchedEffect above). */
private data class StorageStats(
    val localBooks: List<Pair<LibraryItemEntity, File>> = emptyList(),
    val sizesByUuid: Map<String, Long> = emptyMap(),
    val totalSpace: Long = 0L,
    val artworkSpace: Long = 0L
)
