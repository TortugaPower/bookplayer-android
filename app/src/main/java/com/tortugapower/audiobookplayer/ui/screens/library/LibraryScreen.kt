@file:OptIn(ExperimentalMaterial3Api::class)

package com.tortugapower.audiobookplayer.ui.screens.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material.icons.outlined.ErrorOutline
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.tortugapower.audiobookplayer.logic.hasQueuedUploadTask
import com.tortugapower.audiobookplayer.logic.removeLocalFile
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.logic.ImportManager
import com.tortugapower.audiobookplayer.logic.PlaybackManager
import com.tortugapower.audiobookplayer.repository.BoundConversionException
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
    // PLAIN repository for offload ("Remove from device"): must NOT be the syncing wrapper — an
    // offload never enqueues server tasks (the cloud copy survives by design).
    val plainRepository = remember { RoomLibraryRepository(context.applicationContext, database.libraryDao()) }
    val scope = rememberCoroutineScope()

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
    var showDownloadUrlDialog by remember { mutableStateOf(false) }
    var showChooseDestinationDialog by remember { mutableStateOf(false) }
    var showExistingFoldersSheet by remember { mutableStateOf(false) }
    var showItemDetailSheet by remember { mutableStateOf(false) }
    var itemToDetail by remember { mutableStateOf<LibraryItemEntity?>(null) }
    var showSearchScreen by remember { mutableStateOf(false) }
    var showCombineToVolumeDialog by remember { mutableStateOf(false) }
    var showAddFilesDialog by remember { mutableStateOf(false) }
    var showSwipeOptionsDialog by remember { mutableStateOf(false) }
    var itemForSwipeOptions by remember { mutableStateOf<LibraryItemEntity?>(null) }
    var itemToCancelDownload by remember { mutableStateOf<LibraryItemEntity?>(null) }
    // iOS parity: offloading a file with a queued upload gets an extra Warning (shared with Storage Management).
    var offloadWarningItem by remember { mutableStateOf<LibraryItemEntity?>(null) }

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

    // iOS parity (loadingState.error alert): a rejected folder→volume conversion — the option is
    // enabled like iOS's and the rule ("only books, never empty") is enforced at execute time.
    val boundConversionError by libraryViewModel.boundConversionError.collectAsState()
    boundConversionError?.let { reason ->
        AlertDialog(
            onDismissRequest = { libraryViewModel.clearBoundConversionError() },
            title = { Text(stringResource(R.string.common_error)) },
            text = {
                Text(
                    stringResource(
                        when (reason) {
                            BoundConversionException.Reason.NOT_ONLY_BOOKS -> R.string.library_bound_conversion_only_books
                            BoundConversionException.Reason.EMPTY_FOLDER -> R.string.library_bound_conversion_empty
                        }
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { libraryViewModel.clearBoundConversionError() }) {
                    Text(stringResource(R.string.common_ok))
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant
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
                        // Move whatever is selected — the swipe-options Move flow selects a single
                        // item WITHOUT entering select mode. Create + move must run in ONE
                        // coroutine (iOS parity: ItemListViewModel.createFolder(with:items:)):
                        // two separate launches race, and the syncing move resolves the destination
                        // folder by path — losing the race yields a "" destination uuid, i.e. a
                        // server-side move to the library root.
                        val selectedItems = items.filter { it.uuid in selectedItemUuids }
                        if (selectedItems.isNotEmpty()) {
                            libraryViewModel.createFolderAndMoveItems(context, folderName, selectedItems, currentPath)
                            isSelectMode = false
                            selectedItemUuids = emptySet()
                        } else {
                            libraryViewModel.createFolder(folderName)
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

    if (showDownloadUrlDialog) {
        var url by remember { mutableStateOf("") }
        val trimmedUrl = url.trim()
        val isUrlValid = android.webkit.URLUtil.isNetworkUrl(trimmedUrl)

        AlertDialog(
            onDismissRequest = { showDownloadUrlDialog = false },
            title = { Text(stringResource(R.string.library_download_from_url)) },
            text = {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.library_download_url_label)) },
                    singleLine = true,
                    isError = url.isNotEmpty() && !isUrlValid,
                    supportingText = {
                        if (url.isNotEmpty() && !isUrlValid) {
                            Text(stringResource(R.string.library_download_url_error))
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
                        // Same staging pipeline as media-server downloads and file picks:
                        // dedup, archive expansion, and the import confirmation sheet.
                        val fileName = ImportManager.fileNameFromUrl(trimmedUrl)
                        importViewModel.startDownload(context = context, url = trimmedUrl, fileName = fileName)
                        showDownloadUrlDialog = false
                    },
                    enabled = isUrlValid
                ) {
                    Text(stringResource(R.string.common_download))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDownloadUrlDialog = false }) {
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
                val singleFolder = itemsToDelete.singleOrNull()?.takeIf { it.type == ItemType.FOLDER }
                val message = if (itemsToDelete.size == 1) {
                    stringResource(R.string.library_delete_item_message, itemsToDelete[0].title)
                } else {
                    stringResource(R.string.library_delete_items_message, itemsToDelete.size)
                }

                val hasFolder = itemsToDelete.any { it.type == ItemType.FOLDER }
                // Single folder: iOS's shallow-delete explainer replaces the generic warning.
                val warning = when {
                    singleFolder != null -> "\n\n" + stringResource(R.string.library_delete_folder_shallow_desc)
                    hasFolder -> stringResource(R.string.library_delete_folder_warning)
                    else -> ""
                }

                Text(message + warning)
            },
            confirmButton = {
                val singleFolder = itemsToDelete.singleOrNull()?.takeIf { it.type == ItemType.FOLDER }
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            libraryViewModel.deleteSelectedItems(context, itemsToDelete)
                            itemsToDelete = emptyList()
                            isSelectMode = false
                            selectedItemUuids = emptySet()
                        }
                    ) {
                        Text(
                            // iOS: a single folder distinguishes deep vs shallow delete.
                            text = if (singleFolder != null) stringResource(R.string.library_delete_deep)
                                   else stringResource(R.string.common_delete),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (singleFolder != null) {
                        TextButton(
                            onClick = {
                                libraryViewModel.shallowDeleteFolder(context, singleFolder)
                                itemsToDelete = emptyList()
                                isSelectMode = false
                                selectedItemUuids = emptySet()
                            }
                        ) {
                            Text(stringResource(R.string.library_delete_shallow))
                        }
                    }
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

    if (showAddFilesDialog) {
        AlertDialog(
            onDismissRequest = { showAddFilesDialog = false },
            title = { Text(stringResource(R.string.library_add_files_dialog_title)) },
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            showAddFilesDialog = false
                            launcher.launch(arrayOf("audio/*"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.library_add_files_option_import))
                    }
                    Button(
                        onClick = {
                            showAddFilesDialog = false
                            // The ONE download-from-URL dialog (URLUtil validation, canonical
                            // fileNameFromUrl, and the full import staging pipeline).
                            showDownloadUrlDialog = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.library_download_from_url))
                    }
                    Button(
                        onClick = {
                            showAddFilesDialog = false
                            onNavigateToMediaServers()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.media_servers_title))
                    }
                    Button(
                        onClick = {
                            showAddFilesDialog = false
                            showCreateFolderDialog = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.library_create_folder_title))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddFilesDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }


    if (showSwipeOptionsDialog && itemForSwipeOptions != null) {
        val item = itemForSwipeOptions!!
        // Download tri-state for the iOS-parity Download/Cancel/Remove option below.
        val swipeDownloadState by remember(item.uuid, item.relativePath, item.type) {
            libraryViewModel.itemDownloadStateFlow(item)
        }.collectAsState(initial = null)
        AlertDialog(
            onDismissRequest = {
                showSwipeOptionsDialog = false
            },
            title = { Text(item.title) },
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // iOS parity — same order as ItemListView.itemOptionsDialog: Details, Move,
                    // Share, Jump to start, (Android extra: Add Shortcut), Mark finished, Volume
                    // toggle, Download block (subscribed), Delete last.
                    Button(
                        onClick = {
                            showSwipeOptionsDialog = false
                            itemToDetail = item
                            showItemDetailSheet = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.library_see_details), textAlign = TextAlign.Center)
                    }
                    Button(
                        onClick = {
                            showSwipeOptionsDialog = false
                            selectedItemUuids = setOf(item.uuid)
                            showChooseDestinationDialog = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.common_move), textAlign = TextAlign.Center)
                    }
                    // Beyond iOS on purpose: containers are shareable too — they export the audio
                    // files currently on device under their directory (disk truth, not the
                    // download-state flow, which reports FOLDERs as always-local).
                    val shareable by produceState(false, item.uuid, item.relativePath) {
                        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            hasShareableContent(context, item)
                        }
                    }
                    Button(
                        onClick = {
                            showSwipeOptionsDialog = false
                            scope.launch { shareItems(context, listOf(item)) }
                        },
                        enabled = shareable,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.common_share), textAlign = TextAlign.Center)
                    }
                    Button(
                        onClick = {
                            showSwipeOptionsDialog = false
                            libraryViewModel.jumpToStart(listOf(item))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.library_jump_to_start), textAlign = TextAlign.Center)
                    }
                    if (item.type == ItemType.BOOK || item.type == ItemType.BOUND) {
                        Button(
                            onClick = {
                                showSwipeOptionsDialog = false
                                ShortcutHelper.requestPinShortcut(context, item)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.library_add_shortcut), textAlign = TextAlign.Center)
                        }
                    }
                    Button(
                        onClick = {
                            showSwipeOptionsDialog = false
                            libraryViewModel.setFinishedStatus(listOf(item), !item.isFinished)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (item.isFinished) stringResource(R.string.player_mark_as_unfinished)
                                   else stringResource(R.string.player_mark_as_finished),
                            textAlign = TextAlign.Center
                        )
                    }
                    // Volume toggle, iOS logic: BOUND → Convert to Folder; otherwise Combine into
                    // Volume, enabled for a single FOLDER (direct convert) and disabled for a
                    // single BOOK (needs 2+, which means multi-select).
                    if (item.type == ItemType.BOUND) {
                        Button(
                            onClick = {
                                showSwipeOptionsDialog = false
                                libraryViewModel.convertVolumesToFolders(listOf(item))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.library_convert_to_folder), textAlign = TextAlign.Center)
                        }
                    } else {
                        Button(
                            onClick = {
                                showSwipeOptionsDialog = false
                                libraryViewModel.convertFoldersToVolumes(context, listOf(item))
                            },
                            enabled = item.type == ItemType.FOLDER,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.library_combine_into_volume), textAlign = TextAlign.Center)
                        }
                    }
                    // Download block (iOS: rendered only for subscribers, syncService.isActive).
                    if (canSyncLibrary) {
                        when {
                            swipeDownloadState?.isDownloading == true -> Button(
                                onClick = {
                                    showSwipeOptionsDialog = false
                                    itemToCancelDownload = item
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.library_cancel_download), textAlign = TextAlign.Center)
                            }
                            swipeDownloadState?.isLocal == false -> Button(
                                onClick = {
                                    showSwipeOptionsDialog = false
                                    libraryViewModel.startDownload(item)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.common_download), textAlign = TextAlign.Center)
                            }
                            swipeDownloadState?.isLocal == true -> Button(
                                onClick = {
                                    showSwipeOptionsDialog = false
                                    scope.launch {
                                        if (hasQueuedUploadTask(syncTaskRepository, item.uuid)) {
                                            offloadWarningItem = item
                                        } else {
                                            removeLocalFile(context, plainRepository, item)
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.library_remove_from_device), textAlign = TextAlign.Center)
                            }
                        }
                    }
                    Button(
                        onClick = {
                            showSwipeOptionsDialog = false
                            itemsToDelete = listOf(item)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) {
                        Text(stringResource(R.string.common_delete), textAlign = TextAlign.Center)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showSwipeOptionsDialog = false
                    }
                ) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    // iOS parity: cancelling a download gets a confirmation (ItemListView .cancelDownload alert).
    itemToCancelDownload?.let { item ->
        AlertDialog(
            onDismissRequest = { itemToCancelDownload = null },
            title = { Text(stringResource(R.string.library_cancel_download)) },
            text = { Text(item.title) },
            confirmButton = {
                TextButton(
                    onClick = {
                        libraryViewModel.cancelDownload(item)
                        itemToCancelDownload = null
                    }
                ) {
                    Text(
                        text = stringResource(R.string.library_cancel_download),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToCancelDownload = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    // iOS's uploadTaskAlert (shared wording with Storage Management): the file hasn't reached the
    // cloud yet — removing it destroys the only copy.
    offloadWarningItem?.let { item ->
        AlertDialog(
            onDismissRequest = { offloadWarningItem = null },
            title = { Text(stringResource(R.string.storage_management_warning_title)) },
            text = { Text(stringResource(R.string.storage_management_upload_queued_warning, item.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            removeLocalFile(context, plainRepository, item)
                            offloadWarningItem = null
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
                TextButton(onClick = { offloadWarningItem = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    if (showChooseDestinationDialog) {
        // iOS parity (getAvailableFolders): destinations are the FOLDERs at the CURRENT level,
        // minus the items being moved; "Existing Folder" is disabled when there are none.
        val foldersAtLevel by libraryViewModel.getFoldersForPath(currentPath).collectAsState()
        val availableFolders = foldersAtLevel.filter { it.uuid !in selectedItemUuids }
        AlertDialog(
            onDismissRequest = { showChooseDestinationDialog = false },
            title = { Text(stringResource(R.string.library_choose_destination_title)) },
            text = { Text(stringResource(R.string.library_choose_destination_message)) },
            confirmButton = {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // iOS parity (moveOptionsAlert): Library (root, only when inside a folder),
                    // New Folder, Existing Folder, Combine into Volume (all-books only).
                    if (currentPath != null) {
                        Button(
                            onClick = {
                                showChooseDestinationDialog = false
                                val moving = items.filter { it.uuid in selectedItemUuids }
                                libraryViewModel.moveSelectedItems(context, moving, targetPath = null)
                                isSelectMode = false
                                selectedItemUuids = emptySet()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.library_move_to_library))
                        }
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
                    Button(
                        onClick = {
                            showChooseDestinationDialog = false
                            showExistingFoldersSheet = true
                        },
                        enabled = availableFolders.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.library_existing_folder))
                    }
                    val movingItems = items.filter { it.uuid in selectedItemUuids }
                    Button(
                        onClick = {
                            showChooseDestinationDialog = false
                            showCombineToVolumeDialog = true
                        },
                        enabled = movingItems.size > 1 && movingItems.all { it.type == ItemType.BOOK },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.library_combine_into_volume))
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
        // iOS parity (getAvailableFolders + foldersSelection sheet): only the FOLDERs at the
        // CURRENT level, excluding the items being moved. The previous app-wide container list
        // offered a moved folder's own descendants (path corruption) and bound volumes (items
        // only enter a volume via Combine into Volume) as destinations; moving to the root is
        // the move dialog's "Library" button, not a row here.
        val foldersAtLevel by libraryViewModel.getFoldersForPath(currentPath).collectAsState()
        val selectedItems = remember(selectedItemUuids) { items.filter { it.uuid in selectedItemUuids } }
        val destinationFolders = foldersAtLevel.filter { folder -> selectedItems.none { it.uuid == folder.uuid } }

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
                    items(destinationFolders) { folder ->
                        ListItem(
                            headlineContent = { Text(folder.title) },
                            leadingContent = { Icon(Icons.Default.Folder, contentDescription = null) },
                            modifier = Modifier.clickable {
                                libraryViewModel.moveSelectedItems(context, selectedItems, folder.relativePath)
                                showExistingFoldersSheet = false
                                isSelectMode = false
                                selectedItemUuids = emptySet()
                            }
                        )
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
                        val isSingleItemSelect = selectedItems.size == 1
                        val isSingleBookOrBound = isSingleItemSelect &&
                                (selectedItems[0].type == ItemType.BOOK || selectedItems[0].type == ItemType.BOUND)
                        val singleItem = selectedItems.singleOrNull()
                        val singleDownloadState by remember(singleItem?.uuid, singleItem?.relativePath) {
                            singleItem?.let { libraryViewModel.itemDownloadStateFlow(it) }
                                ?: kotlinx.coroutines.flow.flowOf(null)
                        }.collectAsState(initial = null)

                        // Order per design: Select All in its own leading section (divider-separated,
                        // the Material way to group menu items), then Details, Move, Jump to start,
                        // Mark finished, Volume toggle, Download block (subscription-gated, like
                        // iOS's syncService.isActive), Delete — and the home-screen shortcut in its
                        // own trailing section. Entries stay visible-but-disabled, never hidden.
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_select_all)) },
                            onClick = {
                                showMoreMenu = false
                                selectedItemUuids = items.map { it.uuid }.toSet()
                            },
                            leadingIcon = { Icon(Icons.Default.SelectAll, null) }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.library_see_details)) },
                            enabled = isSingleItemSelect,
                            onClick = {
                                showMoreMenu = false
                                itemToDetail = selectedItems.firstOrNull()
                                showItemDetailSheet = true
                            },
                            leadingIcon = { Icon(Icons.Default.Info, null) }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_move)) },
                            enabled = selectedItems.isNotEmpty(),
                            onClick = {
                                showMoreMenu = false
                                showChooseDestinationDialog = true
                            },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, null) }
                        )
                        // Beyond iOS on purpose: no single-item export constraint (ACTION_SEND_MULTIPLE)
                        // and no book-only gate — containers export their on-device audio files.
                        // Enabled when every selected item has something local to export.
                        val canShareSelection by produceState(false, selectedItemUuids) {
                            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                selectedItems.isNotEmpty() && selectedItems.all { hasShareableContent(context, it) }
                            }
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_share)) },
                            enabled = canShareSelection,
                            onClick = {
                                showMoreMenu = false
                                scope.launch { shareItems(context, selectedItems) }
                            },
                            leadingIcon = { Icon(Icons.Default.Share, null) }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.library_jump_to_start)) },
                            enabled = selectedItems.isNotEmpty(),
                            onClick = {
                                showMoreMenu = false
                                libraryViewModel.jumpToStart(selectedItems)
                                isSelectMode = false
                                selectedItemUuids = emptySet()
                            },
                            leadingIcon = { Icon(Icons.Default.RestartAlt, null) }
                        )

                        val allFinished = selectedItems.isNotEmpty() && selectedItems.all { it.isFinished }
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (allFinished) stringResource(R.string.player_mark_as_unfinished)
                                    else stringResource(R.string.player_mark_as_finished)
                                )
                            },
                            enabled = selectedItems.isNotEmpty(),
                            onClick = {
                                showMoreMenu = false
                                libraryViewModel.setFinishedStatus(selectedItems, !allFinished)
                                isSelectMode = false
                                selectedItemUuids = emptySet()
                            },
                            leadingIcon = { Icon(Icons.Default.CheckCircle, null) }
                        )

                        // Volume toggle, iOS logic: all-BOUND → Convert to Folder; otherwise
                        // Combine into Volume, enabled for 2+ books or a single folder.
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
                        } else {
                            val multipleBooks = selectedItems.size > 1 && selectedItems.all { it.type == ItemType.BOOK }
                            val singleFolder = singleItem?.type == ItemType.FOLDER
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.library_combine_into_volume)) },
                                enabled = multipleBooks || singleFolder,
                                onClick = {
                                    showMoreMenu = false
                                    if (singleFolder) {
                                        libraryViewModel.convertFoldersToVolumes(context, selectedItems)
                                        isSelectMode = false
                                        selectedItemUuids = emptySet()
                                    } else {
                                        showCombineToVolumeDialog = true
                                    }
                                },
                                leadingIcon = { Icon(Icons.Default.AutoStories, null) }
                            )
                        }

                        // Download block (iOS: subscribed + single selection only).
                        if (canSyncLibrary) {
                            when {
                                singleDownloadState?.isDownloading == true -> DropdownMenuItem(
                                    text = { Text(stringResource(R.string.library_cancel_download)) },
                                    enabled = isSingleItemSelect,
                                    onClick = {
                                        showMoreMenu = false
                                        itemToCancelDownload = singleItem
                                    },
                                    leadingIcon = { Icon(Icons.Default.Close, null) }
                                )
                                singleDownloadState?.isLocal == true -> DropdownMenuItem(
                                    text = { Text(stringResource(R.string.library_remove_from_device)) },
                                    enabled = isSingleItemSelect,
                                    onClick = {
                                        showMoreMenu = false
                                        singleItem?.let { item ->
                                            scope.launch {
                                                if (hasQueuedUploadTask(syncTaskRepository, item.uuid)) {
                                                    offloadWarningItem = item
                                                } else {
                                                    removeLocalFile(context, plainRepository, item)
                                                }
                                            }
                                        }
                                        isSelectMode = false
                                        selectedItemUuids = emptySet()
                                    },
                                    leadingIcon = { Icon(Icons.Outlined.Cloud, null) }
                                )
                                else -> DropdownMenuItem(
                                    text = { Text(stringResource(R.string.common_download)) },
                                    enabled = isSingleItemSelect && singleDownloadState?.isLocal == false,
                                    onClick = {
                                        showMoreMenu = false
                                        singleItem?.let { libraryViewModel.startDownload(it) }
                                        isSelectMode = false
                                        selectedItemUuids = emptySet()
                                    },
                                    leadingIcon = { Icon(Icons.Default.Download, null) }
                                )
                            }
                        }

                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_delete)) },
                            enabled = selectedItems.isNotEmpty(),
                            onClick = {
                                showMoreMenu = false
                                itemsToDelete = selectedItems
                            },
                            leadingIcon = { Icon(Icons.Default.Delete, null) }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.library_add_shortcut_to_home_screen)) },
                            enabled = isSingleBookOrBound,
                            onClick = {
                                showMoreMenu = false
                                ShortcutHelper.requestPinShortcut(context, selectedItems[0])
                                isSelectMode = false
                                selectedItemUuids = emptySet()
                            },
                            leadingIcon = { Icon(Icons.Default.Home, null) }
                        )
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
                                // Audio, video (audio-in-container books like m4b are often
                                // classified as video/mp4), and zip archives (zip import).
                                launcher.launch(arrayOf(
                                    "audio/*",
                                    "video/*",
                                    "application/zip",
                                    "application/x-zip-compressed",
                                    "application/lpf+zip"
                                ))
                            },
                            leadingIcon = { Icon(Icons.Default.FileDownload, null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.library_download_from_url)) },
                            onClick = {
                                showMenu = false
                                showDownloadUrlDialog = true
                            },
                            leadingIcon = { Icon(Icons.Default.Link, null) },
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
                        if (path.isNullOrEmpty()) {
                            LibraryEmptyState(
                                onAddFiles = { showAddFilesDialog = true }
                            )
                        } else {
                            FolderEmptyState(
                                onAddFiles = { showAddFilesDialog = true }
                            )
                        }
                    }
                } else {
                    val lazyListState = rememberLazyListState()
                
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

                            val isThisItemForSwipe = itemForSwipeOptions?.uuid == item.uuid
                            LaunchedEffect(dismissState.currentValue) {
                                if (!isSelectMode && dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
                                    itemForSwipeOptions = item
                                    showSwipeOptionsDialog = true
                                }
                            }
                            LaunchedEffect(isThisItemForSwipe, showSwipeOptionsDialog) {
                                if (isThisItemForSwipe && !showSwipeOptionsDialog) {
                                    dismissState.reset()
                                    itemForSwipeOptions = null
                                }
                            }

                            SwipeToDismissBox(
                                state = dismissState,
                                backgroundContent = {
                                    // Native Material treatment (the Gmail pattern): a full-width
                                    // tinted background with ONE icon that emphasizes once the drag
                                    // crosses the commit threshold. M3's SwipeToDismissBox has no
                                    // persistent "revealed buttons" state (that's iOS's swipe-actions
                                    // pattern) — a full swipe commits the single action, which here
                                    // opens the item's options dialog (delete lives safely inside it).
                                    if (!isSelectMode && dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                                        val crossedThreshold = dismissState.targetValue == SwipeToDismissBoxValue.EndToStart
                                        val iconScale by animateFloatAsState(
                                            targetValue = if (crossedThreshold) 1.2f else 1f,
                                            label = "swipeIconScale"
                                        )
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(
                                                    if (crossedThreshold) MaterialTheme.colorScheme.primaryContainer
                                                    else MaterialTheme.colorScheme.surfaceVariant
                                                ),
                                            contentAlignment = Alignment.CenterEnd
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Info,
                                                contentDescription = stringResource(R.string.library_see_details),
                                                tint = if (crossedThreshold) MaterialTheme.colorScheme.onPrimaryContainer
                                                       else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier
                                                    .padding(end = 24.dp)
                                                    .graphicsLayer {
                                                        scaleX = iconScale
                                                        scaleY = iconScale
                                                    }
                                            )
                                        }
                                    }
                                },
                                enableDismissFromStartToEnd = false,
                                // Swiping must not fight the select-mode drag-reorder gesture.
                                enableDismissFromEndToStart = !isSelectMode
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
                                        canDownload = canSyncLibrary,
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
    libraryViewModel: com.tortugapower.audiobookplayer.viewmodel.LibraryViewModel? = null,
    // Tier gate for the artwork's cloud-download affordance. For FREE/signed-out users a missing
    // file can't be re-fetched (TaskAccessPolicy discards their download tasks), so the overlay
    // becomes an error badge instead of a download button that silently does nothing.
    canDownload: Boolean = true
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val context = LocalContext.current

    val externalResources = item.externalResources

    // Aggregate download state (units, task queue, disk truth), derived in the ViewModel OFF the main
    // thread — the same `:core` derivation Wear uses; this composable only collects state and never
    // stats the disk. Reactive end to end: the units flow re-emits when a just-signed-in BOUND's
    // sub-books land, and the task flow re-emits on queue changes (tap → downloading; tasks drained →
    // disk truth re-checked). Null until the first computation lands: render neither the cloud badge
    // nor the ring rather than guessing (prevents a wrong-icon flash while rows scroll in).
    val downloadState by remember(item.uuid, item.relativePath, item.type) {
        libraryViewModel?.itemDownloadStateFlow(item)
            ?: kotlinx.coroutines.flow.flowOf(null)
    }.collectAsState(initial = null)
    val isDownloading = downloadState?.isDownloading == true

    // Whole-item ring fraction (a 2-file bound book fills 0→50%→100%, mirroring Wear/iOS): pure math
    // over the aggregate + the live per-chunk progress of the in-flight files. No disk IO here — the
    // downloaded-unit count came from the ViewModel; completed files count as whole units.
    val taskProgress by SyncStatusManager.taskProgress.collectAsState()
    val downloadProgress: Float? = downloadState?.takeIf { it.isDownloading }?.let { state ->
        com.tortugapower.audiobookplayer.logic.OfflineDownloadManager.downloadProgressFraction(
            downloadedUnits = state.downloadedUnits,
            totalUnits = state.totalUnits,
            inProgressSum = state.inFlightUuids.sumOf { taskProgress[it] ?: 0.0 },
        )
    }

    // Highlight the row of the book currently loaded in the player (library and search share
    // this row composable).
    val currentPlayingItem by PlaybackManager.currentItem.collectAsState()
    val isCurrentlyPlaying = currentPlayingItem?.uuid == item.uuid

    val durationText = if (item.duration > 0) {
        val h = (item.duration / 3600).toInt()
        val m = ((item.duration % 3600) / 60).toInt()
        val s = (item.duration % 60).toInt()
        if (h > 0) stringResource(R.string.duration_hms, h, m, s) else stringResource(R.string.duration_ms, m, s)
    } else ""

    // Shared helper localizes container bare counts ("N Files"/"N Chapters" — every surface uses
    // the same mapping); blanks fall back per type.
    val authorText = com.tortugapower.audiobookplayer.logic.LibraryContentsSync
        .displayDetails(context, item.type, item.author)
        ?.takeIf { it.isNotBlank() }
        ?: if (item.type == ItemType.FOLDER) stringResource(R.string.library_folder_empty)
           else stringResource(R.string.library_unknown_author)

    val progressText = if (item.isFinished) {
        stringResource(R.string.common_completed)
    } else {
        "${(item.percentCompleted * 100).toInt()}% ${stringResource(R.string.common_completed).lowercase()}"
    }

    val showCloud = downloadState?.isLocal == false

    val combinedDescription = if (showCloud && !canDownload) {
        "${item.title}. $authorText. $durationText. $progressText. ${stringResource(R.string.library_audio_unavailable)}"
    } else {
        "${item.title}. $authorText. $durationText. $progressText"
    }

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

        // Explicitly "known not local" (showCloud, computed above): while the state is still
        // resolving (null) show nothing.
        val artworkModifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .then(artworkBackground)

        Box(
            modifier = if (showCloud && !isDownloading && canDownload) {
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
                    // LITE/PRO: tappable cloud (downloadable). FREE/signed-out: the file is missing
                    // and can't be re-fetched — an error badge, not a dead download button.
                    imageVector = if (canDownload) Icons.Outlined.Cloud else Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .size(16.dp),
                    tint = if (canDownload) Color(0xFF4285F4) else MaterialTheme.colorScheme.error
                )
            }
            if (item.type == ItemType.FOLDER) {
                // Small circular corner badge, mirroring iOS's integrations-list folder badge
                // (top-left here; iOS uses top-right) — instead of a big glyph over the artwork.
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(11.dp),
                        tint = Color.White
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Uniform gap between the three labels (iOS parity: BookView's VStack spaces title/author/
        // duration evenly). A plain Column left the gaps to each font's line-height padding, so the
        // bodyLarge title sat farther from the author line than the author sat from the duration —
        // trimming the outer line-height padding makes spacedBy the ONLY source of spacing.
        val trimmedLineHeight = androidx.compose.ui.text.style.LineHeightStyle(
            alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
            trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.Both
        )
        Column(
            modifier = Modifier.weight(1f).clearAndSetSemantics { },
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = item.title.ifBlank { stringResource(R.string.library_unknown_title) },
                color = if (isCurrentlyPlaying) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge.copy(lineHeightStyle = trimmedLineHeight),
                fontWeight = FontWeight.Bold,
                // Long titles wrap onto a second line before ellipsizing.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
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
                    style = MaterialTheme.typography.bodySmall.copy(lineHeightStyle = trimmedLineHeight),
                    maxLines = 1
                )
            }

            if (item.duration > 0) {
                Text(
                    text = durationText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall.copy(lineHeightStyle = trimmedLineHeight),
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
                
                // Only folders trail a disclosure chevron; for books the progress wheel/checkmark
                // floats to the right edge (no phantom 24dp placeholder holding its spot).
                if (item.type == ItemType.FOLDER) {
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
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

@Composable
fun LibraryEmptyState(
    onAddFiles: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // iOS parity (EmptyListView, node == .root): the SAME emptyLibrary illustration iOS
        // ships (layered translucent books + speaker), imported as drawable-nodpi so both
        // platforms' empty libraries are pixel-identical. Like iOS, one universal asset for
        // light and dark.
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(R.drawable.empty_library),
            contentDescription = null,
            modifier = Modifier.size(240.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))
        
        TextButton(
            onClick = onAddFiles,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.library_add_files),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
        }
    }
}


@Composable
fun FolderEmptyState(
    onAddFiles: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // iOS parity (EmptyListView, non-root → the emptyPlaylist asset): a play triangle with
        // list bars — exactly Material's PlaylistPlay, tinted like iOS tints it with the accent.
        Icon(
            imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
            modifier = Modifier.size(140.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))
        
        TextButton(
            onClick = onAddFiles,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.library_add_files),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * "Share" (iOS `export_button`), deliberately expanded beyond iOS: ACTION_SEND_MULTIPLE has no
 * single-item export constraint, so a multi-selection shares in one chooser, and containers
 * (folders/volumes) export every audio file currently on device under their directory.
 */
private suspend fun shareItems(context: android.content.Context, items: List<LibraryItemEntity>) {
    // The directory walk + per-file FileProvider lookups are disk IO — resolve off the main
    // thread; only the chooser launch returns to the caller's (main) context.
    val uris = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val processedDir = java.io.File(context.filesDir, "Processed")
        items.flatMap { item ->
            val relativePath = item.relativePath ?: return@flatMap emptyList()
            val target = java.io.File(processedDir, relativePath)
            val files = when {
                target.isDirectory -> target.walkTopDown().filter { it.isFile }.sortedBy { it.path }.toList()
                target.isFile -> listOf(target)
                else -> emptyList()
            }
            files.map { androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", it) }
        }
    }
    if (uris.isEmpty()) return
    val intent = if (uris.size == 1) {
        android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "audio/*"
            putExtra(android.content.Intent.EXTRA_STREAM, uris.first())
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    } else {
        android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
            type = "audio/*"
            putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    context.startActivity(android.content.Intent.createChooser(intent, items.singleOrNull()?.title))
}

/**
 * True when [item] has audio on device to export: the book's own file, or at least one file under
 * a container's directory. Disk stats — call off the main thread.
 */
private fun hasShareableContent(context: android.content.Context, item: LibraryItemEntity): Boolean {
    val relativePath = item.relativePath ?: return false
    val target = java.io.File(java.io.File(context.filesDir, "Processed"), relativePath)
    return if (target.isDirectory) target.walkTopDown().any { it.isFile } else target.isFile
}
