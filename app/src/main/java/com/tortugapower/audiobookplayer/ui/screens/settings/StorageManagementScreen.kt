package com.tortugapower.audiobookplayer.ui.screens.settings

import android.content.Context
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
import com.tortugapower.audiobookplayer.repository.LibraryRepository
import com.tortugapower.audiobookplayer.repository.RoomLibraryRepository
import com.tortugapower.audiobookplayer.ui.components.BookPlayerTabScaffold
import com.tortugapower.audiobookplayer.ui.components.LocalMiniPlayerInset
import kotlinx.coroutines.Dispatchers
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

    val booksFlow = remember { repository.searchBooks("") }
    val books by booksFlow.collectAsState(initial = emptyList())

    val processedDir = remember { File(context.filesDir, "Processed") }
    val artworkDir = remember { File(context.filesDir, "Artworks") }

    var sortBy by remember { mutableStateOf(SortType.SIZE) }
    var showSortMenu by remember { mutableStateOf(false) }
    var itemToDelete by remember { mutableStateOf<LibraryItemEntity?>(null) }

    val localBooks = remember(books) {
        books.mapNotNull { book ->
            val path = book.relativePath
            if (!path.isNullOrEmpty()) {
                val file = File(processedDir, path)
                if (file.exists() && file.isFile) {
                    book to file
                } else null
            } else null
        }
    }

    val totalSpace = remember(localBooks) {
        if (processedDir.exists()) {
            processedDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        } else 0L
    }

    val artworkSpace = remember(localBooks) {
        if (artworkDir.exists()) {
            artworkDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        } else 0L
    }

    val totalSpaceStr = remember(totalSpace) { Formatter.formatShortFileSize(context, totalSpace) }
    val artworkSpaceStr = remember(artworkSpace) { Formatter.formatShortFileSize(context, artworkSpace) }

    val sortedBooks = remember(localBooks, sortBy) {
        when (sortBy) {
            SortType.SIZE -> localBooks.sortedByDescending { it.second.length() }
            SortType.NAME -> localBooks.sortedBy { it.first.title }
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
                                removeLocalFile(context, repository, item)
                                itemToDelete = null
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

            items(sortedBooks, key = { it.first.uuid }) { (book, file) ->
                val sizeStr = remember(file) { Formatter.formatShortFileSize(context, file.length()) }
                StorageFileListItem(
                    title = book.title,
                    fileName = book.originalFileName ?: file.name,
                    sizeStr = sizeStr,
                    onRemoveClick = { itemToDelete = book }
                )
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
                contentDescription = "Remove File",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .size(28.dp)
                    .clickable { onRemoveClick() }
            )

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
}

@Composable
private fun MaterialTheme.outlineVariantColor(): Color {
    return colorScheme.onSurface.copy(alpha = 0.08f)
}

suspend fun removeLocalFile(context: Context, repository: LibraryRepository, item: LibraryItemEntity) {
    withContext(Dispatchers.IO) {
        val processedDir = File(context.filesDir, "Processed")
        val relativePath = item.relativePath
        if (!relativePath.isNullOrEmpty()) {
            val file = File(processedDir, relativePath)
            if (file.exists()) {
                if (file.isDirectory) {
                    file.deleteRecursively()
                } else {
                    file.delete()
                }
            }
        }
        
        val db = AppDatabase.getDatabase(context)
        val extResources = db.libraryDao().getExternalResourcesForBookSync(item.uuid)
        
        if (extResources.isNotEmpty()) {
            item.relativePath = null
            repository.updateItem(item)
            extResources.forEach { resource ->
                if (resource.syncStatus == "downloaded") {
                    val updatedResource = resource.copy(syncStatus = "stream")
                    repository.saveExternalResource(updatedResource)
                }
            }
        } else {
            repository.deleteItemWithFile(context, item)
        }
    }
}
