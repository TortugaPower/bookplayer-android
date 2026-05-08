package com.tortugapower.audiobookplayer.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material3.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tortugapower.audiobookplayer.logic.PlaybackManager
import com.tortugapower.audiobookplayer.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel = viewModel()
) {
    val currentItem = viewModel.currentItem ?: return
    val isPlaying = viewModel.isPlaying
    var position by remember { mutableLongStateOf(viewModel.player?.currentPosition ?: 0L) }
    val duration = (currentItem.duration * 1000).toLong()
    val context = LocalContext.current
    
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    val screenHeightPx = with(LocalDensity.current) { screenHeight.toPx() }
    
    val offsetY = remember { Animatable(screenHeightPx) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(PlaybackManager.showPlayerScreen) {
        if (PlaybackManager.showPlayerScreen) {
            offsetY.animateTo(0f, tween(400))
        } else {
            if (offsetY.value < screenHeightPx) {
                offsetY.animateTo(screenHeightPx, tween(300))
            }
        }
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            position = viewModel.player?.currentPosition ?: 0L
            delay(1000)
        }
    }

    if (viewModel.showControlsSheet) {
        PlayerControlsSheet(
            viewModel = viewModel,
            onDismiss = { viewModel.toggleControlsSheet() },
            onMoreClick = {
                viewModel.toggleControlsSheet()
                viewModel.toggleMoreSettingsSheet()
            }
        )
    }

    if (viewModel.showMoreSettingsSheet) {
        PlayerMoreOptionsSheet(
            viewModel = viewModel,
            onDismiss = { viewModel.toggleMoreSettingsSheet() }
        )
    }

    if (viewModel.showSleepTimerMenu) {
        SleepTimerSheet(
            viewModel = viewModel,
            onDismiss = { viewModel.toggleSleepTimerMenu() }
        )
    }

    if (viewModel.showCustomSleepTimerPicker) {
        CustomSleepTimerPicker(
            viewModel = viewModel,
            onDismiss = { viewModel.toggleCustomSleepTimerPicker() }
        )
    }

    if (viewModel.showBookmarkConfirmation) {
        BookmarkConfirmationDialog(
            time = viewModel.currentBookmark?.time ?: 0.0,
            isExisting = viewModel.isExistingBookmark,
            onAddNote = {
                viewModel.showBookmarkConfirmation = false
                viewModel.showAddNoteDialog = true
            },
            onSeeBookmarks = {
                viewModel.showBookmarkConfirmation = false
                viewModel.showBookmarksList = true
            },
            onDismiss = { viewModel.showBookmarkConfirmation = false }
        )
    }

    if (viewModel.showAddNoteDialog) {
        AddNoteDialog(
            initialNote = viewModel.currentBookmark?.note ?: "",
            onConfirm = { viewModel.updateBookmarkNote(it) },
            onDismiss = { viewModel.showAddNoteDialog = false }
        )
    }

    if (viewModel.showBookmarksList) {
        BookmarksListSheet(
            viewModel = viewModel,
            onDismiss = { viewModel.showBookmarksList = false }
        )
    }

    if (viewModel.showChaptersList) {
        ChaptersListSheet(
            viewModel = viewModel,
            onDismiss = { viewModel.showChaptersList = false }
        )
    }

    if (offsetY.value < screenHeightPx || PlaybackManager.showPlayerScreen) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(0, offsetY.value.roundToInt()) }
                .background(MaterialTheme.colorScheme.background)
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta ->
                        val newValue = (offsetY.value + delta).coerceAtLeast(0f)
                        scope.launch { offsetY.snapTo(newValue) }
                    },
                    onDragStopped = { velocity ->
                        if (offsetY.value > screenHeightPx * 0.3f || velocity > 1000) {
                            scope.launch {
                                offsetY.animateTo(screenHeightPx, tween(300))
                                PlaybackManager.showPlayerScreen = false
                            }
                        } else {
                            scope.launch {
                                offsetY.animateTo(0f, tween(300))
                            }
                        }
                    }
                )
                .statusBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .padding(vertical = 12.dp)
                        .width(40.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                )

                Spacer(modifier = Modifier.height(20.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.secondary
                                )
                            )
                        ),
                    contentAlignment = Alignment.TopEnd
                ) {
                    IconButton(
                        onClick = { },
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Cast,
                            contentDescription = "Cast",
                            tint = MaterialTheme.colorScheme.onSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { }) {
                        Icon(
                            imageVector = Icons.Default.ChevronLeft,
                            contentDescription = "Prev",
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Text(
                        text = currentItem.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { }) {
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = "Next",
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Slider(
                    value = if (duration > 0) position.toFloat() / duration else 0f,
                    onValueChange = { /* Handle seek */ },
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatTime(position),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Chapter 1 of 1",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val remaining = duration - position
                    Text(
                        text = "-${formatTime(remaining)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(48.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SeekButton(isForward = false) { viewModel.seekBackward() }

                    IconButton(
                        onClick = { viewModel.togglePlayPause() },
                        modifier = Modifier.size(80.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    SeekButton(isForward = true) { viewModel.seekForward() }
                }

                Spacer(modifier = Modifier.weight(1f))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 32.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    PlayerBottomButton(
                        label = "${if (viewModel.playbackSpeed % 1.0f == 0.0f) viewModel.playbackSpeed.toInt() else viewModel.playbackSpeed}x",
                        onClick = { viewModel.toggleControlsSheet() }
                    )
                    PlayerBottomButton(
                        icon = Icons.Default.NightsStay,
                        label = if (viewModel.sleepTimerActive) viewModel.sleepTimerRemaining else null,
                        onClick = { viewModel.toggleSleepTimerMenu() }
                    )
                    PlayerBottomButton(
                        icon = Icons.Default.BookmarkBorder,
                        onClick = { viewModel.addBookmark() }
                    )
                    PlayerBottomButton(
                        icon = Icons.AutoMirrored.Filled.List,
                        onClick = { viewModel.showBookmarksList = true }
                    )
                    PlayerBottomButton(
                        icon = Icons.Default.MoreHoriz,
                        onClick = { viewModel.toggleMoreSettingsSheet() }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChaptersListSheet(
    viewModel: PlayerViewModel,
    onDismiss: () -> Unit
) {
    val chapters by viewModel.chapters.collectAsState()
    val sheetState = rememberModalBottomSheetState()
    val currentPosition = viewModel.player?.currentPosition ?: 0L
    
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(40.dp)) // Spacer
                Text(
                    text = "Chapters",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(
                        containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        contentColor = MaterialTheme.colorScheme.primary
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Done")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))

            LazyColumn(
                modifier = Modifier.fillMaxWidth()
            ) {
                items(chapters) { chapter ->
                    val isPlaying = currentPosition >= (chapter.start * 1000) && 
                                    currentPosition < ((chapter.start + chapter.duration) * 1000)
                    
                    Surface(
                        onClick = { viewModel.seekToChapter(chapter) },
                        color = Color.Transparent,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(vertical = 16.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = chapter.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Normal
                                )
                                Text(
                                    text = "Start: ${formatTime((chapter.start * 1000).toLong())} - Duration: ${formatTime((chapter.duration * 1000).toLong())}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                            if (isPlaying) {
                                Icon(
                                    Icons.Default.Check, 
                                    contentDescription = null, 
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerMoreOptionsSheet(
    viewModel: PlayerViewModel,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BookmarkDialogButton(text = "Chapters") {
                viewModel.showMoreSettingsSheet = false
                viewModel.showChaptersList = true
            }
            BookmarkDialogButton(text = "Jump to start") {
                viewModel.jumpToStart()
            }
            BookmarkDialogButton(text = viewModel.currentItem?.let { if (it.isFinished) "Mark as Unfinished" else "Mark as Finished" } ?: "Mark as Finished") {
                viewModel.toggleFinished()
            }
            BookmarkDialogButton(text = if (viewModel.isRepeatEnabled) "Turn off Repeat" else "Turn on Repeat for this book") {
                viewModel.toggleRepeat()
            }
        }
    }
}

@Composable
fun BookmarkConfirmationDialog(
    time: Double,
    isExisting: Boolean,
    onAddNote: () -> Unit,
    onSeeBookmarks: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (isExisting) "Bookmark already exists at ${formatTime((time * 1000).toLong())}" 
                       else "Your bookmark has been saved at ${formatTime((time * 1000).toLong())}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!isExisting) {
                    BookmarkDialogButton(text = "Add note", onClick = onAddNote)
                }
                BookmarkDialogButton(text = "See bookmarks", onClick = onSeeBookmarks)
                BookmarkDialogButton(text = "OK", onClick = onDismiss)
            }
        },
        confirmButton = {},
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(28.dp),
        properties = DialogProperties(usePlatformDefaultWidth = true)
    )
}

@Composable
private fun BookmarkDialogButton(
    text: String,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
            contentColor = MaterialTheme.colorScheme.primary
        ),
        shape = RoundedCornerShape(26.dp),
        elevation = null
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun AddNoteDialog(
    initialNote: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var note by remember { mutableStateOf(initialNote) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Add note",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    placeholder = { Text("Note") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                            contentColor = MaterialTheme.colorScheme.primary
                        ),
                        shape = RoundedCornerShape(26.dp),
                        elevation = null
                    ) {
                        Text("Cancel", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { onConfirm(note) },
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                            contentColor = MaterialTheme.colorScheme.primary
                        ),
                        shape = RoundedCornerShape(26.dp),
                        elevation = null
                    ) {
                        Text("OK", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        confirmButton = {},
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(28.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksListSheet(
    viewModel: PlayerViewModel,
    onDismiss: () -> Unit
) {
    val bookmarks by viewModel.bookmarks.collectAsState()
    val sheetState = rememberModalBottomSheetState()
    
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), CircleShape)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.primary)
                }
                Text(
                    text = "Bookmarks",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Box(modifier = Modifier.size(40.dp))
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                "Manual",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 16.dp)
            )

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(bookmarks) { bookmark ->
                    Surface(
                        onClick = { viewModel.seekToBookmark(bookmark) },
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = formatTime((bookmark.time * 1000).toLong()),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            if (bookmark.note != null && bookmark.note!!.isNotEmpty()) {
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = bookmark.note!!,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerControlsSheet(
    viewModel: PlayerViewModel,
    onDismiss: () -> Unit, 
    onMoreClick: () -> Unit
) {
    val context = LocalContext.current
    var currentSpeed by remember { mutableStateOf(viewModel.playbackSpeed) }
    var currentVolume by remember { mutableStateOf(viewModel.playbackVolume) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), CircleShape)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.primary)
                }
                Text(
                    text = "Player Controls",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                TextButton(
                    onClick = onMoreClick,
                    colors = ButtonDefaults.textButtonColors(
                        containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                        contentColor = MaterialTheme.colorScheme.primary
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("More")
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Set playback speed", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${if (currentSpeed % 1.0f == 0.0f) currentSpeed.toInt() else currentSpeed}x",
                    fontWeight = FontWeight.Bold
                )
            }
            
            Slider(
                value = currentSpeed,
                onValueChange = { 
                    currentSpeed = it
                    viewModel.setPlaybackSpeed(context, it)
                },
                valueRange = 0.5f..4.0f,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.onSurface,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                )
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("0.5", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Text("4.0", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                QuickSpeedButton(icon = Icons.Default.Remove) {
                    currentSpeed = (currentSpeed - 0.1f).coerceAtLeast(0.5f)
                    viewModel.setPlaybackSpeed(context, currentSpeed)
                }
                QuickSpeedLabelButton("1x", currentSpeed == 1.0f) {
                    currentSpeed = 1.0f
                    viewModel.setPlaybackSpeed(context, 1.0f)
                }
                QuickSpeedLabelButton("2x", currentSpeed == 2.0f) {
                    currentSpeed = 2.0f
                    viewModel.setPlaybackSpeed(context, 2.0f)
                }
                QuickSpeedLabelButton("3x", currentSpeed == 3.0f) {
                    currentSpeed = 3.0f
                    viewModel.setPlaybackSpeed(context, 3.0f)
                }
                QuickSpeedButton(icon = Icons.Default.Add) {
                    currentSpeed = (currentSpeed + 0.1f).coerceAtMost(4.0f)
                    viewModel.setPlaybackSpeed(context, currentSpeed)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
            Spacer(modifier = Modifier.height(24.dp))

            Text("Volume", style = MaterialTheme.typography.bodyLarge)
            Slider(
                value = currentVolume,
                onValueChange = { 
                    currentVolume = it
                    viewModel.setPlaybackVolume(context, it)
                },
                valueRange = 0.0f..1.0f,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.onSurface,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Boost Volume", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Doubles the volume.\nUse with caution and care for your hearing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Switch(
                    checked = viewModel.volumeBoost,
                    onCheckedChange = { viewModel.toggleVolumeBoost(context) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomSleepTimerPicker(
    viewModel: PlayerViewModel,
    onDismiss: () -> Unit
) {
    var selectedHours by remember { mutableIntStateOf(1) }
    var selectedMinutes by remember { mutableIntStateOf(0) }
    val sheetState = rememberModalBottomSheetState()
    
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), CircleShape)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.primary)
                }
                Text(
                    text = "Custom Timer",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Box(modifier = Modifier.size(40.dp))
            }

            Spacer(modifier = Modifier.height(32.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TimeWheelPicker(
                    range = 0..23,
                    selectedValue = selectedHours,
                    onValueChange = { selectedHours = it },
                    label = "hours",
                    modifier = Modifier.weight(1f)
                )
                TimeWheelPicker(
                    range = 0..59,
                    selectedValue = selectedMinutes,
                    onValueChange = { selectedMinutes = it },
                    label = "min",
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = {
                    val totalMinutes = (selectedHours * 60) + selectedMinutes
                    if (totalMinutes > 0) {
                        viewModel.startSleepTimer(totalMinutes)
                        onDismiss()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Start Timer", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TimeWheelPicker(
    range: IntRange,
    selectedValue: Int,
    onValueChange: (Int) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = selectedValue
    )
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)
    
    // Update selected value based on scroll
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            onValueChange(listState.firstVisibleItemIndex)
        }
    }

    Box(
        modifier = modifier.height(150.dp),
        contentAlignment = Alignment.Center
    ) {
        // Selection highlight
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
        )
        
        LazyColumn(
            state = listState,
            flingBehavior = flingBehavior,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 55.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            items((range.last - range.first + 1)) { index ->
                val value = range.first + index
                val isSelected = value == selectedValue
                Box(
                    modifier = Modifier
                        .height(40.dp)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (label == "hours") value.toString() else String.format("%02d", value),
                        style = if (isSelected) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
        
        // Label (e.g., "hours", "min") positioned to the right of the numbers
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(
    viewModel: PlayerViewModel,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 48.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), CircleShape)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.primary)
                }
                Text(
                    text = "Sleep Timer",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Box(modifier = Modifier.size(40.dp))
            }

            Spacer(modifier = Modifier.height(32.dp))

            val options = listOf(
                "Off" to 0,
                "5 minutes" to 5,
                "10 minutes" to 10,
                "15 minutes" to 15,
                "30 minutes" to 30,
                "45 minutes" to 45,
                "1 hour" to 60,
                "End of Chapter" to -1
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                options.forEach { (label, minutes) ->
                    val isSelected = (minutes == 0 && !viewModel.sleepTimerActive)
                    BookmarkDialogButton(
                        text = label,
                        onClick = {
                            if (minutes == 0) {
                                viewModel.stopSleepTimer()
                            } else if (minutes == -1) {
                                // TODO: Implement End of Chapter
                                onDismiss()
                            } else {
                                viewModel.startSleepTimer(minutes)
                            }
                        }
                    )
                }
                
                BookmarkDialogButton(text = "Custom") {
                    viewModel.toggleSleepTimerMenu()
                    viewModel.toggleCustomSleepTimerPicker()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MorePlayerSettingsSheet(
    viewModel: PlayerViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    
    LaunchedEffect(Unit) {
        viewModel.loadSettings(context)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), CircleShape)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.primary)
                }
                Text(
                    text = "Player Controls",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Box(modifier = Modifier.size(40.dp))
            }

            Spacer(modifier = Modifier.height(24.dp))

            SettingsSectionLabel("Skip Intervals")
            Surface(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column {
                    SettingsRowPicker("Rewind", "30 secs") { /* Placeholder */ }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    SettingsRowPicker("Forward", "30 secs") { /* Placeholder */ }
                }
            }
            Text(
                "Adjust the amount skipped when using the buttons in the Player or Control Center.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 8.dp, start = 8.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Surface(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column {
                    SettingsRowToggle("Smart Rewind", viewModel.smartRewind) {
                        viewModel.updateSmartRewind(context, it)
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    SettingsRowPicker("Smart Rewind Limit", "${viewModel.smartRewindLimit} secs") { /* Placeholder */ }
                }
            }
            Text(
                "Automatically skip backwards when resuming playback. Skips back further the longer playback has been paused, up to a maximum of 30 secs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 8.dp, start = 8.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Surface(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                shape = RoundedCornerShape(16.dp)
            ) {
                SettingsRowToggle("Auto Sleep Timer", viewModel.autoSleep) {
                    viewModel.updateAutoSleep(context, it)
                }
            }
            Text(
                "Restart the last active sleep timer when playback is resumed",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 8.dp, start = 8.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Surface(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                shape = RoundedCornerShape(16.dp)
            ) {
                SettingsRowToggle("Boost Volume", viewModel.volumeBoost) {
                    viewModel.toggleVolumeBoost(context)
                }
            }
            Text(
                "Doubles the volume.\nUse with caution and care for your hearing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 8.dp, start = 8.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))
            SettingsSectionLabel("Speed")
            Surface(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column {
                    SettingsRowPicker("Quick Action 1", "${if (viewModel.quickAction1 % 1.0f == 0.0f) viewModel.quickAction1.toInt() else viewModel.quickAction1}x") { /* Placeholder */ }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    SettingsRowPicker("Quick Action 2", "${if (viewModel.quickAction2 % 1.0f == 0.0f) viewModel.quickAction2.toInt() else viewModel.quickAction2}x") { /* Placeholder */ }
                }
            }
        }
    }
}

@Composable
fun SettingsSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
        modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
    )
}

@Composable
fun SettingsRowToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}

@Composable
fun SettingsRowPicker(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            Icon(Icons.Default.UnfoldMore, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
fun QuickSpeedButton(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(48.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), CircleShape)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun QuickSpeedLabelButton(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontWeight = FontWeight.Bold, color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun SeekButton(isForward: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(CircleShape)
            .clickable { onClick() }
            .padding(8.dp)
    ) {
        Icon(
            imageVector = if (isForward) Icons.Default.Forward30 else Icons.Default.Replay30,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(60.dp)
        )
    }
}

@Composable
fun PlayerBottomButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null, 
    label: String? = null,
    onClick: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .height(48.dp)
            .widthIn(min = 48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
            .clickable { onClick() }
            .padding(horizontal = if (label != null) 12.dp else 0.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
                if (label != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                }
            }
            if (label != null) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}
