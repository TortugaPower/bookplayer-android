@file:OptIn(ExperimentalMaterial3Api::class)

package com.tortugapower.audiobookplayer.ui.screens.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tortugapower.audiobookplayer.logic.ItemArtwork
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.logic.ImportManager
import com.tortugapower.audiobookplayer.logic.PlaybackManager
import com.tortugapower.audiobookplayer.logic.ShortcutHelper
import com.tortugapower.audiobookplayer.logic.SyncStatusManager
import com.tortugapower.audiobookplayer.logic.SyncTaskFactory
import com.tortugapower.audiobookplayer.repository.RoomAccountRepository
import com.tortugapower.audiobookplayer.repository.RoomLibraryRepository
import com.tortugapower.audiobookplayer.repository.RoomSyncTaskRepository
import com.tortugapower.audiobookplayer.ui.components.BookPlayerTabScaffold
import com.tortugapower.audiobookplayer.ui.components.LocalMiniPlayerInset
import com.tortugapower.audiobookplayer.viewmodel.ImportViewModel
import com.tortugapower.audiobookplayer.viewmodel.LibraryViewModel
import android.app.Application
import com.tortugapower.audiobookplayer.viewmodel.LibraryViewModelFactory

/** Duration of the horizontal slide between library folders. */
private const val FolderNavDurationMillis = 400

@Composable
fun LibraryScreen(
    importViewModel: ImportViewModel = viewModel(),
    viewModel: LibraryViewModel? = null,
    onNavigateToMediaServers: () -> Unit = {}
) {
    val context = LocalContext.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    
    // Inject repositories for sync tasks
    val database = remember { AppDatabase.getDatabase(context) }
    val syncTaskRepository = remember { RoomSyncTaskRepository(database.syncTaskDao()) }
    val accountRepository = remember { RoomAccountRepository(database.accountDao()) }

    val libraryViewModel: LibraryViewModel = viewModel ?: viewModel(
        factory = LibraryViewModelFactory(context.applicationContext as Application, RoomLibraryRepository(context.applicationContext, database.libraryDao()), syncTaskRepository)
    )

    val currentPath by libraryViewModel.currentPath.collectAsState()
    val account by accountRepository.getAccountFlow().collectAsState(initial = null)
    // Cloud sync is available on PRO/LITE — gates both the auto-fetch on navigation and pull-to-refresh.
    val canSyncLibrary = account?.tier == AccountTier.PRO || account?.tier == AccountTier.LITE

    val isRefreshing by libraryViewModel.isRefreshing.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val syncTasksBusyMessage = stringResource(R.string.library_sync_tasks_busy)
    // A pull-to-refresh that lands while sync jobs are queued is declined (see LibraryViewModel.refresh);
    // surface that as a transient note rather than silently doing nothing.
    LaunchedEffect(Unit) {
        libraryViewModel.syncTasksBusy.collect {
            snackbarHostState.showSnackbar(syncTasksBusyMessage)
        }
    }

    // Fetch contents with throttle when path or account changes (e.g. login)
    LaunchedEffect(currentPath, account) {
        val pathKey = currentPath ?: "root"

        if (canSyncLibrary) {
            if (SyncStatusManager.canFetchContents(pathKey)) {
                if (SyncTaskFactory.createFetchContentsTask(syncTaskRepository, currentPath)) {
                    SyncStatusManager.markPathAsFetched(pathKey)
                }
            }
        }
    }
    
    // Fetch data for the actual current path (used by dialogs and actions)
    val items by libraryViewModel.getItemsForPath(currentPath).collectAsState()
    val availableFolders by libraryViewModel.getFoldersForPath(currentPath).collectAsState()

    var selectedItemUuids by remember { mutableStateOf(setOf<String>()) }
    var isSelectMode by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var itemsToDelete by remember { mutableStateOf<List<LibraryItemEntity>>(emptyList()) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showChooseDestinationDialog by remember { mutableStateOf(false) }
    var showExistingFoldersSheet by remember { mutableStateOf(false) }
    var showItemDetailSheet by remember { mutableStateOf(false) }
    var itemToDetail by remember { mutableStateOf<LibraryItemEntity?>(null) }
    var showSearchScreen by remember { mutableStateOf(false) }
    var showCombineToVolumeDialog by remember { mutableStateOf(false) }

    if (showCombineToVolumeDialog) {
        val selectedItems = remember(selectedItemUuids) { items.filter { it.uuid in selectedItemUuids } }
        var volumeName by remember { mutableStateOf(selectedItems.firstOrNull()?.title ?: "") }
        val focusRequester = remember { FocusRequester() }
        val expectedRelativePath = if (currentPath == null) volumeName else "$currentPath/$volumeName"
        val isNameDuplicate = items.any { it.relativePath?.equals(expectedRelativePath, ignoreCase = true) == true }
        val isNameValid = volumeName.isNotEmpty() && volumeName.all { it.isLetterOrDigit() || it == ' ' || it == '_' || it == '-' } && !isNameDuplicate

        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }

        AlertDialog(
            onDismissRequest = { showCombineToVolumeDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.library_combine_to_volume),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.library_combine_to_volume_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    TextField(
                        value = volumeName,
                        onValueChange = { volumeName = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .border(
                                width = 1.dp, 
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(12.dp)
                            ),
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        placeholder = { Text(stringResource(R.string.library_combine_to_volume_title)) }
                    )

                    if (volumeName.isNotEmpty() && (isNameDuplicate || !isNameValid)) {
                        Text(
                            text = if (isNameDuplicate) stringResource(R.string.library_combine_to_volume_error) else stringResource(R.string.library_folder_name_error),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { showCombineToVolumeDialog = false },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Text(stringResource(R.string.common_cancel))
                    }
                    Button(
                        onClick = {
                            val selectedItems = items.filter { it.uuid in selectedItemUuids }
                            libraryViewModel.combineToVolume(context, selectedItems, volumeName)
                            showCombineToVolumeDialog = false
                            isSelectMode = false
                            selectedItemUuids = emptySet()
                        },
                        enabled = isNameValid,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text(stringResource(R.string.common_create))
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(28.dp)
        )
    }

    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
        onResult = { uris -> if (uris.isNotEmpty()) ImportManager.startImport(context, uris) }
    )

    BackHandler(enabled = isSelectMode || currentPath != null || showSearchScreen) {
        if (showSearchScreen) {
            showSearchScreen = false
            libraryViewModel.updateSearchQuery("")
        } else if (isSelectMode) {
            isSelectMode = false
            selectedItemUuids = emptySet()
        } else {
            libraryViewModel.navigateBack()
        }
    }

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
        val expectedRelativePath = if (currentPath == null) folderName else "$currentPath/$folderName"
        val isNameDuplicate = items.any { it.relativePath?.equals(expectedRelativePath, ignoreCase = true) == true }
        val isNameValid = folderName.isNotEmpty() && folderName.all { it.isLetterOrDigit() || it == '_' || it == '-' } && !isNameDuplicate

        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text(stringResource(R.string.library_create_folder_title)) },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text(stringResource(R.string.library_folder_name_label)) },
                    singleLine = true,
                    isError = folderName.isNotEmpty() && (!isNameValid || isNameDuplicate),
                    supportingText = {
                        if (folderName.isNotEmpty()) {
                            if (isNameDuplicate) {
                                Text(stringResource(R.string.library_folder_name_exists_error))
                            } else if (!isNameValid) {
                                Text(stringResource(R.string.library_folder_name_error))
                            }
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
                    Text(stringResource(R.string.common_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (itemsToDelete.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { itemsToDelete = emptyList() },
            title = { 
                Text(
                    if (itemsToDelete.size == 1) stringResource(R.string.library_delete_item_title)
                    else stringResource(R.string.library_delete_items_title, itemsToDelete.size)
                )
            },
            text = {
                val message = if (itemsToDelete.size == 1) {
                    stringResource(R.string.library_delete_item_message, itemsToDelete[0].title)
                } else {
                    stringResource(R.string.library_delete_items_message, itemsToDelete.size)
                }
                
                val hasFolder = itemsToDelete.any { it.type == ItemType.FOLDER }
                val warning = if (hasFolder) stringResource(R.string.library_delete_folder_warning) else ""
                
                Text(message + warning)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        libraryViewModel.deleteSelectedItems(context, itemsToDelete)
                        itemsToDelete = emptyList()
                        isSelectMode = false
                        selectedItemUuids = emptySet()
                    }
                ) {
                    Text(stringResource(R.string.common_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { itemsToDelete = emptyList() }) {
                    Text(stringResource(R.string.common_cancel))
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
            title = { Text(stringResource(R.string.library_choose_destination_title)) },
            text = { Text(stringResource(R.string.library_choose_destination_message)) },
            confirmButton = {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            showChooseDestinationDialog = false
                            showExistingFoldersSheet = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.library_existing_folder))
                    }
                    Button(
                        onClick = {
                            showChooseDestinationDialog = false
                            showCreateFolderDialog = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.library_new_folder))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showChooseDestinationDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (showExistingFoldersSheet) {
        val containers by libraryViewModel.getAllContainers().collectAsState()
        val selectedItems = remember(selectedItemUuids) { items.filter { it.uuid in selectedItemUuids } }

        ModalBottomSheet(
            onDismissRequest = { showExistingFoldersSheet = false },
            containerColor = MaterialTheme.colorScheme.surface,
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    text = stringResource(R.string.library_select_folder_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(16.dp)
                )
                
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    item {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.library_title_default)) },
                            leadingContent = { Icon(Icons.Default.AutoStories, null) },
                            modifier = Modifier.clickable {
                                libraryViewModel.moveSelectedItems(context, selectedItems, null)
                                showExistingFoldersSheet = false
                                isSelectMode = false
                                selectedItemUuids = emptySet()
                            }
                        )
                    }

                    items(containers.filter { container -> selectedItems.none { it.uuid == container.uuid } }) { container ->
                        ListItem(
                            headlineContent = { Text(container.title) },
                            leadingContent = { 
                                Icon(
                                    imageVector = if (container.type == ItemType.FOLDER) Icons.Default.Folder else Icons.Default.AutoStories,
                                    contentDescription = null
                                )
                            },
                            modifier = Modifier.clickable {
                                libraryViewModel.moveSelectedItems(context, selectedItems, container.relativePath)
                                showExistingFoldersSheet = false
                                isSelectMode = false
                                selectedItemUuids = emptySet()
                            }
                        )
                    }
                    
                    if (containers.isEmpty()) {
                        item {
                            Text(
                                text = "No folders found",
                                modifier = Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    BookPlayerTabScaffold(
        title = if (isSelectMode) {
            stringResource(R.string.library_title_default) 
        } else {
            currentPath?.substringAfterLast('/') ?: stringResource(R.string.library_title_default)
        },
        navigationIcon = {
            when {
                isSelectMode -> IconButton(onClick = {
                    isSelectMode = false
                    selectedItemUuids = emptySet()
                }) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_cancel))
                }
                currentPath != null -> IconButton(onClick = { libraryViewModel.navigateBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                }
            }
        },
        actions = {
            if (isSelectMode) {
                IconButton(
                    onClick = { if (selectedItemUuids.isNotEmpty()) showChooseDestinationDialog = true },
                    enabled = selectedItemUuids.isNotEmpty(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = stringResource(R.string.common_move))
                }
                IconButton(
                    onClick = { itemsToDelete = items.filter { it.uuid in selectedItemUuids } },
                    enabled = selectedItemUuids.isNotEmpty(),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.common_delete))
                }
                Box {
                    IconButton(onClick = { showMoreMenu = true }) {
                        Icon(Icons.Default.MoreHoriz, contentDescription = stringResource(R.string.common_more))
                    }
                    DropdownMenu(
                        expanded = showMoreMenu,
                        onDismissRequest = { showMoreMenu = false },
                    ) {
                        val selectedItems = items.filter { it.uuid in selectedItemUuids }
                        
                        if (selectedItems.size == 1 && (selectedItems[0].type == ItemType.BOOK || selectedItems[0].type == ItemType.BOUND)) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.library_see_details)) },
                                onClick = {
                                    showMoreMenu = false
                                    itemToDetail = selectedItems[0]
                                    showItemDetailSheet = true
                                },
                                leadingIcon = { Icon(Icons.Default.Info, null) }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.library_add_shortcut_to_home_screen)) },
                                onClick = {
                                    showMoreMenu = false
                                    ShortcutHelper.requestPinShortcut(context, selectedItems[0])
                                    isSelectMode = false
                                    selectedItemUuids = emptySet()
                                },
                                leadingIcon = { Icon(Icons.Default.Home, null) }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_select_all)) },
                            onClick = {
                                showMoreMenu = false
                                selectedItemUuids = items.map { it.uuid }.toSet()
                            },
                            leadingIcon = { Icon(Icons.Default.SelectAll, null) }
                        )

                        if (selectedItems.size >= 2 && selectedItems.all { it.type == ItemType.BOOK }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.library_combine_to_volume)) },
                                onClick = {
                                    showMoreMenu = false
                                    showCombineToVolumeDialog = true
                                },
                                leadingIcon = { Icon(Icons.Default.AutoStories, null) }
                            )
                        }

                        if (selectedItems.isNotEmpty() && selectedItems.all { it.type == ItemType.BOUND }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.library_convert_to_folder)) },
                                onClick = {
                                    showMoreMenu = false
                                    libraryViewModel.convertVolumesToFolders(selectedItems)
                                    isSelectMode = false
                                    selectedItemUuids = emptySet()
                                },
                                leadingIcon = { Icon(Icons.Default.FolderOpen, null) }
                            )
                        }

                        if (selectedItems.isNotEmpty() && selectedItems.all { it.type == ItemType.FOLDER }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.library_convert_to_volume)) },
                                onClick = {
                                    showMoreMenu = false
                                    libraryViewModel.convertFoldersToVolumes(context, selectedItems)
                                    isSelectMode = false
                                    selectedItemUuids = emptySet()
                                },
                                leadingIcon = { Icon(Icons.Default.AutoStories, null) }
                            )
                        }
                    }
                }
            } else {
                if (importViewModel.activeDownloadCount > 0) {
                    IconButton(onClick = { importViewModel.showImportSheet = true }) {
                        BadgedBox(
                            badge = {
                                Badge {
                                    val count = importViewModel.activeDownloadCount
                                    Text(if (count > 9) "9+" else count.toString())
                                }
                            }
                        ) {
                            Icon(
                                Icons.Default.FileDownload,
                                contentDescription = stringResource(R.string.common_download),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                IconButton(onClick = { showSearchScreen = true }) {
                    Icon(Icons.Default.Search, contentDescription = stringResource(R.string.common_search))
                }
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.common_more))
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_select)) },
                            onClick = {
                                showMenu = false
                                isSelectMode = true
                            },
                            leadingIcon = { Icon(Icons.Default.Checklist, null) },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.import_title)) },
                            onClick = {
                                showMenu = false
                                launcher.launch(arrayOf("audio/*"))
                            },
                            leadingIcon = { Icon(Icons.Default.FileDownload, null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.media_servers_title)) },
                            onClick = {
                                showMenu = false
                                onNavigateToMediaServers()
                            },
                            leadingIcon = { Icon(Icons.Default.Dns, null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.library_create_folder_title)) },
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { libraryViewModel.refresh(syncEnabled = canSyncLibrary) },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
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
                modifier = Modifier.fillMaxSize(),
            ) { path ->
                val pathItems by remember(path) { libraryViewModel.getItemsForPath(path) }.collectAsState()
            
                // Local state to handle reordering during selection mode
                val reorderableItems = remember(pathItems, isSelectMode) { 
                    pathItems.toMutableStateList() 
                }

                if (pathItems.isEmpty()) {
                    // Scrollable so pull-to-refresh works on an empty folder (the main reason to refresh).
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(stringResource(R.string.library_empty_message), color = Color.Gray)
                    }
                } else {
                    val lazyListState = rememberLazyListState()
                    val density = LocalDensity.current
                
                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier.fillMaxSize(),
                        // Reserve space for the floating mini player so the last item clears it
                        // while the list still scrolls behind the pill.
                        contentPadding = PaddingValues(bottom = LocalMiniPlayerInset.current)
                    ) {
                        itemsIndexed(reorderableItems, key = { _, it -> it.uuid }) { index, item ->
                            val isSelected = selectedItemUuids.contains(item.uuid)
                            val dismissState = rememberSwipeToDismissBoxState()

                            LaunchedEffect(dismissState.currentValue) {
                                if (!isSelectMode && dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
                                    itemsToDelete = listOf(item)
                                    dismissState.reset()
                                }
                            }

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
                                        libraryViewModel = libraryViewModel,
                                        modifier = if (isSelectMode) {
                                            Modifier.pointerInput(item.uuid, reorderableItems) {
                                                var dragAccumulator = 0f
                                                detectDragGesturesAfterLongPress(
                                                    onDragStart = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress) },
                                                    onDragEnd = { libraryViewModel.reorderItems(reorderableItems.toList()) },
                                                    onDrag = { change, dragAmount ->
                                                        change.consume()
                                                        dragAccumulator += dragAmount.y
                                                    
                                                        val currentIndex = reorderableItems.indexOfFirst { it.uuid == item.uuid }
                                                        if (currentIndex == -1) return@detectDragGesturesAfterLongPress
                                                    
                                                        val threshold = with(density) { 64.dp.toPx() }
                                                    
                                                        if (dragAccumulator > threshold && currentIndex < reorderableItems.size - 1) {
                                                            reorderableItems[currentIndex] = reorderableItems[currentIndex + 1]
                                                            reorderableItems[currentIndex + 1] = item
                                                            dragAccumulator = 0f
                                                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                                        } else if (dragAccumulator < -threshold && currentIndex > 0) {
                                                            reorderableItems[currentIndex] = reorderableItems[currentIndex - 1]
                                                            reorderableItems[currentIndex - 1] = item
                                                            dragAccumulator = 0f
                                                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                                        }
                                                    }
                                                )
                                            }
                                        } else Modifier,
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
                                                    val isCurrentlyPlaying = PlaybackManager.currentItem.value?.uuid == item.uuid
                                                    if (isCurrentlyPlaying) {
                                                        PlaybackManager.setShowPlayer(true)
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

    if (showSearchScreen) {
        SearchScreen(
            viewModel = libraryViewModel,
            onDismiss = { 
                showSearchScreen = false 
                libraryViewModel.updateSearchQuery("")
            }
        )
    }
}

@Composable
fun LibraryListItem(
    item: LibraryItemEntity,
    isSelected: Boolean = false,
    isSelectMode: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    libraryViewModel: com.tortugapower.audiobookplayer.viewmodel.LibraryViewModel? = null
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val context = LocalContext.current

    val externalResources = item.externalResources

    // The BOOK files behind this row: a BOOK is itself (no DB hit); a BOUND observes its children as a
    // reactive Room flow — NOT a one-shot query, which right after sign-in could run before fetch_contents
    // inserted the sub-books and leave the row permanently blind to its own download (no ring, stale cloud).
    // Downloads, progress, and the local/cloud state all aggregate over these units — the same `:core`
    // model the Wear standalone library uses.
    val downloadUnits by remember(item.uuid, item.relativePath, item.type) {
        libraryViewModel?.downloadUnitsFlow(item) ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsState(initial = if (item.type == ItemType.BOOK) listOf(item) else emptyList())

    // Task-queue-driven "downloading" state (shared one-per-screen observation): true from the tap until
    // every unit's task is gone (completion, cancel, or terminal failure) — not just while bytes flow.
    val activeDownloadUuids by (libraryViewModel?.activeDownloadUuids
        ?: remember { MutableStateFlow(emptySet()) }).collectAsState()
    val isDownloading = downloadUnits.any { it.uuid in activeDownloadUuids }

    // Whole-item progress over the units (a 2-file bound book fills 0→50%→100%), mirroring Wear/iOS.
    val taskProgress by SyncStatusManager.taskProgress.collectAsState()
    val downloadProgress: Float? = if (!isDownloading) null else {
        // Snapshot the whole-unit count on task transitions (not per byte-tick): the processor streams
        // straight into the final path, so a unit only counts as downloaded once its task is gone — see
        // OfflineDownloadManager.unitDownloaded. Keyed on the active-task set so a completed file re-counts
        // exactly when its task is deleted, without re-stat-ing the disk on every progress emission.
        val downloadedCount = remember(downloadUnits, activeDownloadUuids) {
            downloadUnits.count {
                com.tortugapower.audiobookplayer.logic.OfflineDownloadManager.unitDownloaded(
                    fileExists = com.tortugapower.audiobookplayer.logic.OfflineDownloadManager.isFileDownloaded(context, it.relativePath),
                    taskActive = it.uuid in activeDownloadUuids,
                )
            }
        }
        com.tortugapower.audiobookplayer.logic.OfflineDownloadManager.downloadProgressFraction(
            downloadedUnits = downloadedCount,
            totalUnits = downloadUnits.size,
            // Live fractions only for the in-flight files; completed ones are whole units above.
            inProgressSum = downloadUnits.filter { it.uuid in activeDownloadUuids }.sumOf { taskProgress[it.uuid] ?: 0.0 },
        )
    }

    // Disk truth per unit, re-checked when a download for this row starts/finishes (`isDownloading` flips
    // when the tasks drain — after the processor wrote the file, or deleted it on cancel/failure). Without
    // that key the result is cached and the row keeps its cloud icon after a download completes, until the
    // row is recomposed from scratch (scroll away/back or re-enter the folder). While a BOUND's units are
    // still resolving, fall back to the item's own path check.
    val isLocal = remember(item.relativePath, item.type, downloadUnits, isDownloading) {
        when {
            item.type == ItemType.FOLDER -> true
            downloadUnits.isNotEmpty() -> downloadUnits.all {
                com.tortugapower.audiobookplayer.logic.OfflineDownloadManager.isFileDownloaded(context, it.relativePath)
            }
            item.relativePath == null -> false
            else -> {
                val processedDir = java.io.File(context.filesDir, "Processed")
                java.io.File(processedDir, item.relativePath!!).exists()
            }
        }
    }

    val durationText = if (item.duration > 0) {
        val h = (item.duration / 3600).toInt()
        val m = ((item.duration % 3600) / 60).toInt()
        val s = (item.duration % 60).toInt()
        if (h > 0) stringResource(R.string.duration_hms, h, m, s) else stringResource(R.string.duration_ms, m, s)
    } else ""

    val authorText = if (item.author.isNullOrBlank()) {
        if (item.type == ItemType.FOLDER) stringResource(R.string.library_folder_empty) 
        else stringResource(R.string.library_unknown_author)
    } else item.author!!

    val progressText = if (item.isFinished) {
        stringResource(R.string.common_completed)
    } else {
        "${(item.percentCompleted * 100).toInt()}% ${stringResource(R.string.common_completed).lowercase()}"
    }

    val combinedDescription = "${item.title}. $authorText. $durationText. $progressText"

    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = combinedDescription
                role = Role.Button
            }
            .pointerInput(isSelectMode, onClick, onLongClick) {
                if (isSelectMode) {
                    detectTapGestures(onTap = { onClick() })
                } else {
                    detectTapGestures(
                        onTap = { onClick() },
                        onLongPress = { 
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            onLongClick() 
                        }
                    )
                }
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

        val showCloud = !isLocal
        val artworkModifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .then(artworkBackground)

        Box(
            modifier = if (showCloud && !isDownloading) {
                artworkModifier
                    .clickable(
                        onClickLabel = stringResource(R.string.common_download),
                        // Shared :core orchestration (same as Wear): fans a BOUND book out into its BOOK
                        // files, skips local/queued files, refreshes presigned URLs, starts the sync host.
                        onClick = { libraryViewModel?.startDownload(item) }
                    )
            } else {
                artworkModifier.clearAndSetSemantics { }
            }
        ) {
            if (item.artworkURL != null) {
                AsyncImage(
                    model = item.artworkURL,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else if (
                (item.type == ItemType.BOOK || item.type == ItemType.BOUND || item.type == ItemType.FOLDER) &&
                (!item.relativePath.isNullOrEmpty() || !item.remoteURL.isNullOrEmpty())
            ) {
                // No stored artworkURL: resolve the embedded cover via CoverArtResolver (iOS parity —
                // covers PRO cloud items not yet downloaded). BOOK reads its own file; BOUND loops its
                // sub-books; FOLDER recurses its contents (handleDirectory). Keyed by relativePath so the
                // decoded cover is reused from the memory cache.
                val artworkCacheKey = item.relativePath?.takeIf { it.isNotEmpty() } ?: item.remoteURL
                val artworkRequest = remember(item.relativePath, item.remoteURL) {
                    ImageRequest.Builder(context)
                        .data(ItemArtwork(item.uuid, item.relativePath, item.remoteURL))
                        .memoryCacheKey(artworkCacheKey)
                        .crossfade(true)
                        .build()
                }
                AsyncImage(
                    model = artworkRequest,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            if (downloadProgress != null) {
                // Download Progress Overlay
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier.size(32.dp),
                        color = Color.White,
                        strokeWidth = 3.dp,
                        trackColor = Color.White.copy(alpha = 0.3f)
                    )
                }
            } else if (showCloud) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(size.width, size.height * 0.3f)
                        lineTo(size.width, size.height)
                        lineTo(size.width * 0.3f, size.height)
                        close()
                    }
                    drawPath(path, Color.Black.copy(alpha = 0.65f))
                }
                Icon(
                    imageVector = Icons.Outlined.Cloud,
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .size(16.dp),
                    tint = Color(0xFF4285F4) // Cloud Blue
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

        Column(
            modifier = Modifier.weight(1f).clearAndSetSemantics { }
        ) {
            Text(
                text = item.title.ifBlank { stringResource(R.string.library_unknown_title) },
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (externalResources.isNotEmpty()) {
                    externalResources.forEachIndexed { index, resource ->
                        if (index > 0) {
                            Text(
                                text = "•",
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        
                        val tint = MaterialTheme.colorScheme.onSurfaceVariant
                        
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            when (resource.providerName.lowercase()) {
                                "jellyfin" -> {
                                    Box(
                                        modifier = Modifier.size(12.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Canvas(modifier = Modifier.fillMaxSize()) {
                                            val path = androidx.compose.ui.graphics.Path().apply {
                                                moveTo(size.width / 2f, 1f)
                                                lineTo(size.width - 1f, size.height - 1f)
                                                lineTo(1f, size.height - 1f)
                                                close()
                                            }
                                            drawPath(
                                                path = path,
                                                color = tint,
                                                style = androidx.compose.ui.graphics.drawscope.Stroke(
                                                    width = 1.5.dp.toPx(),
                                                    join = androidx.compose.ui.graphics.StrokeJoin.Round
                                                )
                                            )
                                        }
                                    }
                                }
                                "audiobookshelf" -> {
                                    Icon(
                                        imageVector = Icons.Default.Book,
                                        contentDescription = null,
                                        tint = tint,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                                else -> {
                                    Icon(
                                        imageVector = Icons.Default.Layers,
                                        contentDescription = null,
                                        tint = tint,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                            
                            val providerLabel = resource.providerName.lowercase().replaceFirstChar {
                                if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString()
                            }
                            
                            Text(
                                text = providerLabel,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1
                            )
                        }
                    }
                    
                    Text(
                        text = "•",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Text(
                    text = authorText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1
                )
            }

            if (item.duration > 0) {
                Text(
                    text = durationText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        if (isSelectMode) {
            Icon(
                imageVector = Icons.Default.Reorder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp).clearAndSetSemantics { }
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clearAndSetSemantics { }
            ) {
                if (item.isFinished) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
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
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                } else if (item.type == ItemType.BOUND) {
                    Icon(
                        imageVector = Icons.Default.Layers,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
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
