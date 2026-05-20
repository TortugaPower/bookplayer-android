@file:OptIn(ExperimentalMaterial3Api::class)

package com.tortugapower.audiobookplayer.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import coil.compose.AsyncImage
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.logic.ImportManager
import com.tortugapower.audiobookplayer.logic.PlaybackManager
import com.tortugapower.audiobookplayer.repository.RoomLibraryRepository
import com.tortugapower.audiobookplayer.ui.components.BookPlayerTabScaffold
import com.tortugapower.audiobookplayer.viewmodel.ImportViewModel
import com.tortugapower.audiobookplayer.viewmodel.LibraryViewModel
import com.tortugapower.audiobookplayer.viewmodel.LibraryViewModelFactory

/** Duration of the horizontal slide between library folders. */
private const val FolderNavDurationMillis = 400

@Composable
fun LibraryScreen(
    importViewModel: ImportViewModel = viewModel()
) {
    val context = LocalContext.current
    val database = remember { AppDatabase.getDatabase(context) }
    val repository = remember { RoomLibraryRepository(database.libraryDao()) }
    val libraryViewModel: LibraryViewModel = viewModel(
        factory = LibraryViewModelFactory(repository)
    )

    val currentPath by libraryViewModel.currentPath.collectAsState()
    
    // Fetch data for the actual current path (used by dialogs and actions)
    val items by libraryViewModel.getItemsForPath(currentPath).collectAsState()
    val availableFolders by libraryViewModel.getFoldersForPath(currentPath).collectAsState()

    var selectedItemUuids by remember { mutableStateOf(setOf<String>()) }
    var isSelectMode by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
        onResult = { uris -> if (uris.isNotEmpty()) ImportManager.startImport(context, uris) }
    )

    BackHandler(enabled = isSelectMode || currentPath != null) {
        if (isSelectMode) {
            isSelectMode = false
            selectedItemUuids = emptySet()
        } else {
            libraryViewModel.navigateBack()
        }
    }

    var itemsToDelete by remember { mutableStateOf<List<LibraryItemEntity>>(emptyList()) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showChooseDestinationDialog by remember { mutableStateOf(false) }
    var showExistingFoldersSheet by remember { mutableStateOf(false) }
    var showItemDetailSheet by remember { mutableStateOf(false) }
    var itemToDetail by remember { mutableStateOf<LibraryItemEntity?>(null) }

    if (showItemDetailSheet && itemToDetail != null) {
        ItemDetailSheet(
            item = itemToDetail!!,
            viewModel = libraryViewModel,
            onDismiss = { 
                showItemDetailSheet = false 
                itemToDetail = null
            }
        )
    }

    if (showCreateFolderDialog) {
        var folderName by remember { mutableStateOf("") }
        val isNameValid = folderName.isNotEmpty() && folderName.all { it.isLetterOrDigit() || it == '_' || it == '-' }

        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text("Create Folder") },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text("Folder Name") },
                    singleLine = true,
                    isError = folderName.isNotEmpty() && !isNameValid,
                    supportingText = {
                        if (folderName.isNotEmpty() && !isNameValid) {
                            Text("Use only letters, numbers, '-' or '_'")
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        libraryViewModel.createFolder(folderName)
                        if (isSelectMode && selectedItemUuids.isNotEmpty()) {
                            val selectedItems = items.filter { it.uuid in selectedItemUuids }
                            val newPath = if (currentPath == null) folderName else "$currentPath/$folderName"
                            libraryViewModel.moveSelectedItems(context, selectedItems, newPath)
                            isSelectMode = false
                            selectedItemUuids = emptySet()
                        }
                        showCreateFolderDialog = false
                    },
                    enabled = isNameValid
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) {
                    Text("Cancel")
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (showChooseDestinationDialog) {
        AlertDialog(
            onDismissRequest = { showChooseDestinationDialog = false },
            title = { Text("Choose Destination") },
            text = { Text("Where would you like to move the selected items?") },
            confirmButton = {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            showChooseDestinationDialog = false
                            showCreateFolderDialog = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("New Folder")
                    }
                    Button(
                        onClick = {
                            showChooseDestinationDialog = false
                            showExistingFoldersSheet = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = availableFolders.isNotEmpty()
                    ) {
                        Text("Existing Folder")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showChooseDestinationDialog = false }) {
                    Text("Cancel")
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (showExistingFoldersSheet) {
        ModalBottomSheet(
            onDismissRequest = { showExistingFoldersSheet = false },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp).padding(horizontal = 16.dp)) {
                Text(
                    text = "Select Folder",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(availableFolders) { folder ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val selectedItems = items.filter { it.uuid in selectedItemUuids }
                                    libraryViewModel.moveSelectedItems(context, selectedItems, folder.relativePath)
                                    showExistingFoldersSheet = false
                                    isSelectMode = false
                                    selectedItemUuids = emptySet()
                                }
                                .padding(vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(text = folder.title, color = MaterialTheme.colorScheme.onSurface)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))
                    }
                }
            }
        }
    }

    if (itemsToDelete.isNotEmpty()) {
        val title = if (itemsToDelete.size == 1) "Delete Item" else "Delete ${itemsToDelete.size} Items"
        val message = if (itemsToDelete.size == 1) {
            "Are you sure you want to delete '${itemsToDelete.first().title}'? This will also remove the physical file."
        } else {
            "Are you sure you want to delete these ${itemsToDelete.size} items? This will also remove the physical files."
        }
        val hasFolder = itemsToDelete.any { it.type == ItemType.FOLDER }
        val folderWarning = if (hasFolder) "\n\nNote: Deleting a folder will also delete all of its contents." else ""

        AlertDialog(
            onDismissRequest = { itemsToDelete = emptyList() },
            title = { Text(title) },
            text = { Text(message + folderWarning) },
            confirmButton = {
                TextButton(
                    onClick = {
                        libraryViewModel.deleteSelectedItems(context, itemsToDelete)
                        itemsToDelete = emptyList()
                        isSelectMode = false
                        selectedItemUuids = emptySet()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFE57373))
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { itemsToDelete = emptyList() }) {
                    Text("Cancel")
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    BookPlayerTabScaffold(
        title = if (isSelectMode) {
            stringResource(R.string.library_selected_count, selectedItemUuids.size)
        } else {
            currentPath?.substringAfterLast('/') ?: stringResource(R.string.library_title)
        },
        navigationIcon = {
            when {
                isSelectMode -> IconButton(onClick = {
                    isSelectMode = false
                    selectedItemUuids = emptySet()
                }) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel))
                }
                currentPath != null -> IconButton(onClick = { libraryViewModel.navigateBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            }
        },
        actions = {
            if (isSelectMode) {
                IconButton(
                    onClick = { if (selectedItemUuids.isNotEmpty()) showChooseDestinationDialog = true },
                    enabled = selectedItemUuids.isNotEmpty(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = stringResource(R.string.action_move))
                }
                IconButton(
                    onClick = { itemsToDelete = items.filter { it.uuid in selectedItemUuids } },
                    enabled = selectedItemUuids.isNotEmpty(),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete))
                }
                Box {
                    IconButton(onClick = { showMoreMenu = true }) {
                        Icon(Icons.Default.MoreHoriz, contentDescription = stringResource(R.string.action_more))
                    }
                    DropdownMenu(
                        expanded = showMoreMenu,
                        onDismissRequest = { showMoreMenu = false },
                    ) {
                        if (selectedItemUuids.size == 1) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_see_details)) },
                                onClick = {
                                    showMoreMenu = false
                                    val selected = items.find { it.uuid in selectedItemUuids }
                                    if (selected != null && selected.type == ItemType.BOOK) {
                                        itemToDetail = selected
                                        showItemDetailSheet = true
                                    }
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_select_all)) },
                            onClick = {
                                showMoreMenu = false
                                selectedItemUuids = items.map { it.uuid }.toSet()
                            },
                        )
                    }
                }
            } else {
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.action_more))
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_select)) },
                            onClick = {
                                showMenu = false
                                isSelectMode = true
                            },
                            leadingIcon = { Icon(Icons.Default.Checklist, null) },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_import)) },
                            onClick = {
                                showMenu = false
                                launcher.launch(arrayOf("audio/*"))
                            },
                            leadingIcon = { Icon(Icons.Default.FileDownload, null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_create_folder)) },
                            onClick = {
                                showMenu = false
                                showCreateFolderDialog = true
                            },
                            leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        AnimatedContent(
            targetState = currentPath,
            transitionSpec = {
                if (targetState != null && (initialState == null || targetState!!.length > (initialState?.length ?: 0))) {
                    slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(FolderNavDurationMillis)) togetherWith
                    slideOutHorizontally(targetOffsetX = { -it }, animationSpec = tween(FolderNavDurationMillis))
                } else {
                    slideInHorizontally(initialOffsetX = { -it }, animationSpec = tween(FolderNavDurationMillis)) togetherWith
                    slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(FolderNavDurationMillis))
                }
            },
            label = "FolderNavigation",
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) { path ->
            val pathItems by remember(path) { libraryViewModel.getItemsForPath(path) }.collectAsState()

            if (pathItems.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.library_empty), color = Color.Gray)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(pathItems, key = { it.uuid }) { item ->
                        val isSelected = selectedItemUuids.contains(item.uuid)
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = {
                                if (!isSelectMode && it == SwipeToDismissBoxValue.EndToStart) {
                                    itemsToDelete = listOf(item)
                                    false
                                } else false
                            }
                        )

                        SwipeToDismissBox(
                            state = dismissState,
                            backgroundContent = {
                                val isSwiping = !isSelectMode && dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart
                                val color = if (isSwiping) Color(0xFFE57373) else Color.Transparent
                                
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(color)
                                        .clickable { 
                                            if (isSwiping) itemsToDelete = listOf(item)
                                        }
                                        .padding(horizontal = 24.dp),
                                    contentAlignment = Alignment.CenterEnd
                                ) {
                                    if (isSwiping) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Delete",
                                            tint = Color.White
                                        )
                                    }
                                }
                            },
                            enableDismissFromStartToEnd = false
                        ) {
                            Surface(
                                color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else MaterialTheme.colorScheme.background,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                LibraryListItem(
                                    item = item,
                                    isSelected = isSelected,
                                    isSelectMode = isSelectMode,
                                    onClick = {
                                        if (isSelectMode) {
                                            selectedItemUuids = if (isSelected) {
                                                selectedItemUuids - item.uuid
                                            } else {
                                                selectedItemUuids + item.uuid
                                            }
                                        } else {
                                            if (item.type == ItemType.FOLDER) {
                                                libraryViewModel.navigateTo(item.relativePath ?: "")
                                            } else {
                                                val isCurrentlyPlaying = PlaybackManager.currentItem?.uuid == item.uuid
                                                if (isCurrentlyPlaying) {
                                                    PlaybackManager.showPlayerScreen = true
                                                    if (PlaybackManager.player?.isPlaying == false) {
                                                        PlaybackManager.player?.play()
                                                    }
                                                } else {
                                                    PlaybackManager.playItem(context, item)
                                                }
                                            }
                                        }
                                    },
                                    onLongClick = {
                                        if (!isSelectMode) {
                                            isSelectMode = true
                                            selectedItemUuids = setOf(item.uuid)
                                        }
                                    }
                                )
                            }
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(start = if (isSelectMode) 120.dp else 80.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        )
                    }
                    }
                }
            }
        }
    }
@Composable
fun LibraryListItem(
    item: LibraryItemEntity,
    isSelected: Boolean = false,
    isSelectMode: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(onClick, onLongClick) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { 
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        onLongClick() 
                    }
                )
            }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSelectMode) {
            Icon(
                imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 16.dp)
            )
        }

        // Thumbnail/Artwork
        val artworkBackground = if (item.artworkURL == null) {
            Modifier.background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary, 
                        MaterialTheme.colorScheme.secondary
                    )
                )
            )
        } else {
            Modifier.background(Color.Transparent)
        }

        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .then(artworkBackground)
        ) {
            if (item.artworkURL != null) {
                AsyncImage(
                    model = item.artworkURL,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            if (item.remoteURL != null) {
                Icon(
                    imageVector = Icons.Outlined.Cloud,
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .size(16.dp),
                    tint = MaterialTheme.colorScheme.onSecondary.copy(alpha = 0.8f)
                )
            }
            if (item.type == ItemType.FOLDER) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    modifier = Modifier.align(Alignment.Center).size(24.dp),
                    tint = Color.White.copy(alpha = 0.8f)
                )
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    text = item.author ?: if (item.type == ItemType.FOLDER) "0 Files" else "Unknown author",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1
                )
                if (item.duration > 0) {
                    Text(
                        text = formatDuration(item.duration),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1
                    )
                }
            }

        Spacer(modifier = Modifier.width(8.dp))

        if (isSelectMode) {
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.isFinished) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Completed",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    PieProgressIcon(
                        progress = item.percentCompleted.toFloat(),
                        modifier = Modifier.size(24.dp)
                    )
                }
                
                if (item.type == ItemType.FOLDER) {
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "Open Folder",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    // Spacer to align with folder's chevron
                    Spacer(modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

@Composable
fun PieProgressIcon(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
) {
    Canvas(modifier = modifier) {
        if (progress > 0) {
            val strokeWidth = 1.5.dp.toPx()
            val radius = (size.minDimension - strokeWidth) / 2
            val center = Offset(size.width / 2, size.height / 2)

            // Outer ring
            drawCircle(
                color = color,
                radius = radius,
                center = center,
                style = Stroke(width = strokeWidth)
            )

            // Pie slice
            val padding = 3.dp.toPx()
            val arcRadius = radius - padding
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = true,
                topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
                size = Size(arcRadius * 2, arcRadius * 2)
            )
        }
    }
}

fun formatDuration(seconds: Double): String {
    val h = (seconds / 3600).toInt()
    val m = ((seconds % 3600) / 60).toInt()
    val s = (seconds % 60).toInt()
    return if (h > 0) "${h}h ${m}m ${s}s" else "${m}m ${s}s"
}
