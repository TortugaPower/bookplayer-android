@file:OptIn(ExperimentalMaterial3Api::class)

package com.tortugapower.audiobookplayer.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.tortugapower.audiobookplayer.ui.UiText
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tortugapower.audiobookplayer.model.ExternalLibraryItem
import com.tortugapower.audiobookplayer.network.ExternalLibraryInfo
import com.tortugapower.audiobookplayer.viewmodel.ExternalLibraryViewModel
import com.tortugapower.audiobookplayer.viewmodel.ImportViewModel
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.tortugapower.audiobookplayer.R

enum class LibraryTab { BOOKS, AUTHORS }

@Composable
fun ExternalLibraryScreen(
    viewModel: ExternalLibraryViewModel,
    importViewModel: ImportViewModel,
    serverName: String,
    onBack: () -> Unit,
    onItemClick: (ExternalLibraryItem) -> Unit,
    onActionStarted: () -> Unit = {},
    onReauthRequested: () -> Unit = {}
) {
    val items by viewModel.items.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val availableLibraries by viewModel.availableLibraries.collectAsState()
    val resolvedLibraryId by viewModel.resolvedLibraryId.collectAsState()
    val noLibraries by viewModel.noLibraries.collectAsState()
    val serverHeaders by viewModel.serverHeaders.collectAsState()
    val activeDownloadCount = importViewModel.activeDownloadCount
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    var showLibraryPicker by remember { mutableStateOf(false) }
    // iOS parity: several libraries and nothing resolved → the picker auto-presents.
    LaunchedEffect(availableLibraries, resolvedLibraryId) {
        if ((availableLibraries?.size ?: 0) > 1 && resolvedLibraryId == null) {
            showLibraryPicker = true
        }
    }

    // iOS parity: expired session gets Connection Details/Cancel only — no Retry. The alert
    // stays up until re-auth succeeds (retryAfterReauth clears the state), so dismissing the
    // re-auth sheet without signing in lands back here instead of on a broken screen.
    val sessionExpiredServerName by viewModel.sessionExpiredServerName.collectAsState()
    sessionExpiredServerName?.let { expiredName ->
        AlertDialog(
            onDismissRequest = onBack,
            title = { Text(stringResource(id = R.string.common_error)) },
            text = { Text(stringResource(id = R.string.media_servers_error_session_expired, expiredName.ifBlank { serverName })) },
            confirmButton = {
                TextButton(onClick = onReauthRequested) {
                    Text(stringResource(id = R.string.media_servers_connection_details_title))
                }
            },
            dismissButton = {
                TextButton(onClick = onBack) {
                    Text(stringResource(id = R.string.common_cancel))
                }
            }
        )
    }

    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(LibraryTab.BOOKS) }
    var activeAuthorFilter by remember { mutableStateOf<String?>(null) }
    
    val focusRequester = remember { FocusRequester() }
    val selectedItems = remember { mutableStateListOf<ExternalLibraryItem>() }
    val isMultiSelectMode by remember { derivedStateOf { selectedItems.isNotEmpty() } }

    val filteredItems = remember(items, searchQuery, activeAuthorFilter) {
        items.filter { item ->
            val matchesSearch = if (searchQuery.isEmpty()) true 
                               else item.entity.title.contains(searchQuery, ignoreCase = true) || 
                                    (item.entity.author?.contains(searchQuery, ignoreCase = true) ?: false)
            val matchesAuthor = if (activeAuthorFilter == null) true
                                else item.entity.author == activeAuthorFilter
            matchesSearch && matchesAuthor
        }
    }

    val authors = remember(items) {
        items.mapNotNull { it.entity.author }.distinct().sorted()
    }

    val filteredAuthors = remember(authors, searchQuery) {
        if (searchQuery.isEmpty()) authors
        else authors.filter { it.contains(searchQuery, ignoreCase = true) }
    }

    LaunchedEffect(isSearchActive) {
        if (isSearchActive) {
            kotlinx.coroutines.delay(100)
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    Scaffold(
        topBar = {
            if (isMultiSelectMode) {
                TopAppBar(
                    title = { Text(stringResource(id = R.string.external_library_selected_items_count, selectedItems.size)) },
                    navigationIcon = {
                        IconButton(onClick = { selectedItems.clear() }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(id = R.string.common_cancel))
                        }
                    },
                    actions = {
                        val downloadFailedMessage = stringResource(id = R.string.external_library_download_failed)
                        IconButton(onClick = {
                            val itemsToDownload = selectedItems.toList()
                            selectedItems.clear()
                            scope.launch {
                                // Same guard as the single-item path: getStreamUrl returns "" when
                                // the server can't be resolved. Only dismiss the flow if something
                                // actually started; otherwise tell the user instead of failing silently.
                                var startedAny = false
                                itemsToDownload.forEach { item ->
                                    val url = viewModel.getStreamUrl(item.entity)
                                    if (url.isNotBlank()) {
                                        val fileName = item.entity.originalFileName ?: "${item.entity.title}.mp3"
                                        importViewModel.startDownload(
                                            context = context,
                                            url = url,
                                            fileName = fileName,
                                            headers = item.customHeaders,
                                            providerName = viewModel.server?.type?.name?.lowercase(),
                                            providerId = item.entity.uuid,
                                            hostId = viewModel.server?.id?.toString()
                                        )
                                        startedAny = true
                                    }
                                }
                                if (startedAny) {
                                    onActionStarted()
                                } else {
                                    android.widget.Toast.makeText(context, downloadFailedMessage, android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }) {
                            Icon(Icons.Default.FileDownload, contentDescription = stringResource(id = R.string.common_download))
                        }
                    }
                )
            } else if (isSearchActive) {
                TopAppBar(
                    title = {
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text(stringResource(id = R.string.common_search)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { 
                            isSearchActive = false
                            searchQuery = ""
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(id = R.string.external_library_stop_search_description))
                        }
                    },
                    actions = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(id = R.string.common_clear))
                            }
                        }
                    }
                )
            } else {
                val titleText = when {
                    activeAuthorFilter != null -> activeAuthorFilter!!
                    selectedTab == LibraryTab.AUTHORS -> stringResource(id = R.string.external_library_authors_tab_title)
                    else -> serverName
                }
                CenterAlignedTopAppBar(
                    title = { Text(titleText) },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (activeAuthorFilter != null) {
                                activeAuthorFilter = null
                            } else {
                                onBack()
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(id = R.string.common_back))
                        }
                    },
                    actions = {
                        // Same rule as iOS: the switch affordance only exists when there is
                        // actually more than one library to switch between.
                        if ((availableLibraries?.size ?: 0) > 1) {
                            IconButton(onClick = { showLibraryPicker = true }) {
                                Icon(Icons.Default.AccountBalance, contentDescription = stringResource(id = R.string.external_library_switch_library))
                            }
                        }
                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(Icons.Default.Search, contentDescription = stringResource(id = R.string.common_search))
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (noLibraries) {
                // ONLY shown when the libraries fetch succeeded with zero eligible results —
                // fetch errors take the error branch below, so this can't appear spuriously.
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.LibraryBooks,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(id = R.string.external_library_no_libraries),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            } else if (error != null && items.isEmpty()) {
                Text(
                    text = error!!.asString(),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    textAlign = TextAlign.Center
                )
            } else if (resolvedLibraryId == null || (isLoading && items.isEmpty())) {
                // Resolving libraries / picker pending / first page loading. Mirrors iOS keeping
                // the browser disabled until a library is resolved.
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (selectedTab == LibraryTab.BOOKS || activeAuthorFilter != null) {
                val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
                
                val shouldLoadMore = remember {
                    derivedStateOf {
                        val layoutInfo = gridState.layoutInfo
                        val totalItemsNumber = layoutInfo.totalItemsCount
                        val lastVisibleItemIndex = (layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) + 1
                        lastVisibleItemIndex > (totalItemsNumber - 10)
                    }
                }
                
                LaunchedEffect(shouldLoadMore.value) {
                    if (shouldLoadMore.value && activeAuthorFilter == null) {
                        viewModel.loadMore()
                    }
                }

                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = 16.dp,
                        end = 16.dp,
                        bottom = 80.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (filteredItems.isEmpty() && !isLoading) {
                        item(span = { GridItemSpan(3) }) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 120.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = null,
                                        modifier = Modifier.size(64.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = stringResource(id = R.string.external_library_no_results_found),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                    if (searchQuery.isNotEmpty()) {
                                        Text(
                                            text = stringResource(id = R.string.external_library_try_different_search_term),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        items(filteredItems) { item ->
                            val isSelected = selectedItems.contains(item)
                            ExternalBookItem(
                                item = item,
                                isSelected = isSelected,
                                onLongClick = {
                                    if (!isSelected) selectedItems.add(item)
                                },
                                onClick = {
                                    if (isMultiSelectMode) {
                                        if (isSelected) selectedItems.remove(item)
                                        else selectedItems.add(item)
                                    } else {
                                        onItemClick(item)
                                    }
                                }
                            )
                        }
                    }
                    
                    if (isLoading) {
                        item(span = { GridItemSpan(3) }) {
                            Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(32.dp))
                            }
                        }
                    }
                }
            } else {
                // Authors Tab
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = 8.dp,
                        bottom = 80.dp
                    )
                ) {
                    if (filteredAuthors.isEmpty() && !isLoading) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillParentMaxSize()
                                    .padding(bottom = 80.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = null,
                                        modifier = Modifier.size(64.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = stringResource(id = R.string.external_library_no_authors_found),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                    if (searchQuery.isNotEmpty()) {
                                        Text(
                                            text = stringResource(id = R.string.external_library_try_different_search_term),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        items(filteredAuthors) { author ->
                            AuthorListItem(
                                author = author,
                                onClick = { activeAuthorFilter = author }
                            )
                        }
                    }
                }
            }

            // Filter Tabs (Books / Authors) - Floating
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
                    .wrapContentWidth(),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                shape = RoundedCornerShape(28.dp),
                tonalElevation = 8.dp,
                shadowElevation = 8.dp,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .height(64.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TabItem(
                        icon = Icons.AutoMirrored.Filled.LibraryBooks, 
                        label = stringResource(id = R.string.external_library_books_tab_label), 
                        isSelected = selectedTab == LibraryTab.BOOKS && activeAuthorFilter == null,
                        onClick = { 
                            selectedTab = LibraryTab.BOOKS
                            activeAuthorFilter = null
                        }
                    )
                    TabItem(
                        icon = Icons.Default.People, 
                        label = stringResource(id = R.string.external_library_authors_tab_label), 
                        isSelected = selectedTab == LibraryTab.AUTHORS || activeAuthorFilter != null,
                        onClick = { 
                            selectedTab = LibraryTab.AUTHORS
                        }
                    )
                }
            }

            // Floating Download Progress
            AnimatedVisibility(
                visible = activeDownloadCount > 0,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(20.dp),
                    tonalElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = if (activeDownloadCount == 1) stringResource(id = R.string.external_library_downloading_one_item)
                                   else stringResource(id = R.string.external_library_downloading_multiple_items, activeDownloadCount),                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }

    if (showLibraryPicker) {
        ModalBottomSheet(
            onDismissRequest = {
                showLibraryPicker = false
                // iOS parity: cancelling with nothing selected leaves the browser entirely;
                // with a selection it just closes the picker.
                if (resolvedLibraryId == null) onBack()
            }
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text(
                    text = stringResource(id = R.string.external_library_picker_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                LazyColumn {
                    items(availableLibraries.orEmpty()) { library ->
                        LibraryPickerRow(
                            library = library,
                            isSelected = library.id == resolvedLibraryId,
                            headers = serverHeaders,
                            onClick = {
                                viewModel.selectLibrary(library.id)
                                showLibraryPicker = false
                            }
                        )
                    }
                }
            }
        }
    }
}

/** A selectable library: 50dp artwork (or placeholder), name, optional caption, checkmark. */
@Composable
private fun LibraryPickerRow(
    library: ExternalLibraryInfo,
    isSelected: Boolean,
    headers: Map<String, String>?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { selected = isSelected }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier
                .size(50.dp)
                .clip(RoundedCornerShape(8.dp)),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            if (library.artworkUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(library.artworkUrl)
                        .apply { headers?.forEach { (k, v) -> addHeader(k, v) } }
                        .build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.LibraryBooks,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = library.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            library.subtitleResId?.let { subtitle ->
                Text(
                    text = stringResource(id = subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExternalBookItem(
    item: ExternalLibraryItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            // Announce multi-select state to screen readers; visually it's only an overlay + check.
            .semantics { selected = isSelected },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .aspectRatio(1f)
                .fillMaxWidth()
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp)),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                if (item.entity.artworkURL != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(item.entity.artworkURL)
                            // External-server covers authenticate via headers, not URL tokens.
                            .apply { item.customHeaders?.forEach { (k, v) -> addHeader(k, v) } }
                            .build(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }

            if (isSelected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f))
                )
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(32.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                        .padding(4.dp)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = item.entity.title.ifBlank { stringResource(id = R.string.library_unknown_title) },
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            lineHeight = 14.sp
        )
        
        Text(
            text = item.entity.author ?: stringResource(id = R.string.library_unknown_author),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun TabItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val color = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    }
    
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    } else {
        Color.Transparent
    }
    
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(80.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            icon, 
            contentDescription = null, 
            tint = color,
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = label, 
            color = color, 
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
fun AuthorListItem(
    author: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(28.dp)
            )
        }
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Text(
            text = author,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}
