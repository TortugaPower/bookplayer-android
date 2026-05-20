package com.tortugapower.audiobookplayer.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.*
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.res.stringResource
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.logic.PlaybackManager
import com.tortugapower.audiobookplayer.viewmodel.PlayerViewModel
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun MarqueeText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    var containerWidth by remember { mutableStateOf(0) }
    var textWidth by remember { mutableStateOf(0) }

    LaunchedEffect(text, containerWidth, textWidth) {
        if (textWidth > containerWidth && containerWidth > 0) {
            while (true) {
                delay(2000) // Initial wait
                scrollState.animateScrollTo(
                    value = textWidth - containerWidth,
                    animationSpec = tween(
                        durationMillis = (textWidth - containerWidth) * 30,
                        easing = LinearEasing
                    )
                )
                delay(2000) // End wait
                scrollState.scrollTo(0)
            }
        }
    }

    Box(
        modifier = modifier
            .onGloballyPositioned { containerWidth = it.size.width }
            .horizontalScroll(scrollState, enabled = false),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = style,
            onTextLayout = { textWidth = it.size.width },
            maxLines = 1,
            overflow = TextOverflow.Visible,
            modifier = Modifier.wrapContentWidth(unbounded = true)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel = viewModel()
) {
    val currentItem = viewModel.currentItem
    val isPlaying = viewModel.isPlaying
    
    // Use the item's saved time as the initial value when the item changes
    var position by remember(currentItem?.uuid) { 
        val initialPos = if (currentItem != null) (currentItem.currentTime * 1000).toLong() else 0L
        mutableLongStateOf(initialPos) 
    }
    
    var isDragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableFloatStateOf(0f) }
    
    val duration = remember(currentItem?.uuid) { ((currentItem?.duration ?: 0.0) * 1000).toLong() }
    val context = LocalContext.current
    
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenHeightPx = remember(configuration, density) {
        with(density) { configuration.screenHeightDp.dp.toPx() }
    }
    
    val offsetY = remember { Animatable(screenHeightPx) }
    val scope = rememberCoroutineScope()
    val isHidden = offsetY.value >= screenHeightPx

    LaunchedEffect(PlaybackManager.showPlayerScreen, screenHeightPx) {
        if (PlaybackManager.showPlayerScreen) {
            offsetY.animateTo(0f, tween(400))
        } else {
            offsetY.animateTo(screenHeightPx, tween(300))
        }
    }

    LaunchedEffect(isPlaying, isDragging, isHidden, viewModel.seekTrigger, currentItem?.uuid, viewModel.isTransitioning) {
        val p = viewModel.player
        if (p != null && !viewModel.isTransitioning) {
            // Final safety guard for Media3 sync lag
            if (p.currentMediaItem?.mediaId != currentItem?.uuid || 
                (p.playbackState != Player.STATE_READY && p.playbackState != Player.STATE_BUFFERING)) {
                
                var attempts = 0
                while ((p.currentMediaItem?.mediaId != currentItem?.uuid || 
                       (p.playbackState != Player.STATE_READY && p.playbackState != Player.STATE_BUFFERING)) 
                       && attempts < 30) {
                    delay(50)
                    attempts++
                }
                // After IDs match and player is ready, give it a substantial moment 
                // to settle its internal position state after seekTo()
                delay(500)
            }

            if (!isDragging && !isHidden && p.currentMediaItem?.mediaId == currentItem?.uuid && !viewModel.isTransitioning) {
                if (isPlaying) {
                    // Update position every second while playing
                    while (isPlaying) {
                        position = p.currentPosition
                        delay(1000)
                    }
                } else {
                    // Sync position once when paused or seek triggered
                    position = p.currentPosition
                }
            }
        }
    }

    if (viewModel.showControlsSheet) {
        PlayerControlsSheet(
            viewModel = viewModel,
            onDismiss = { viewModel.toggleControlsSheet() },
            onMoreClick = {
                viewModel.toggleControlsSheet()
                viewModel.toggleExtendedControls()
            }
        )
    }

    if (viewModel.showExtendedControls) {
        ExtendedControlsSheet(
            viewModel = viewModel,
            onDismiss = { viewModel.toggleExtendedControls() }
        )
    }

    if (viewModel.showMoreOptions) {
        MoreOptionsSheet(
            viewModel = viewModel,
            onDismiss = { viewModel.toggleMoreOptions() }
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .offset { IntOffset(0, offsetY.value.roundToInt()) }
            .graphicsLayer { 
                alpha = if (isHidden) 0f else 1f
            }
            .background(MaterialTheme.colorScheme.background)
            .then(
                if (isHidden) {
                    Modifier // No touch interception at all when hidden
                } else {
                    Modifier.draggable(
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
                }
            )
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        if (currentItem != null) {
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

                val artworkBackground = if (currentItem.artworkURL == null) {
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
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(24.dp))
                        .then(artworkBackground),
                    contentAlignment = Alignment.TopEnd
                ) {
                    if (currentItem.artworkURL != null) {
                        AsyncImage(
                            model = currentItem.artworkURL,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                    IconButton(
                        onClick = { },
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Cast,
                            contentDescription = stringResource(R.string.player_cast),
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
                    IconButton(
                        onClick = { viewModel.playPrevious(context) },
                        enabled = viewModel.hasPreviousItem,
                        modifier = Modifier.offset(x = (-12).dp).alpha(if (viewModel.hasPreviousItem) 1f else 0.3f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ChevronLeft,
                            contentDescription = stringResource(R.string.player_prev),
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    MarqueeText(
                        text = currentItem.title,
                        style = MaterialTheme.typography.titleLarge.copy(
                            color = MaterialTheme.colorScheme.onBackground,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                    )
                    IconButton(
                        onClick = { viewModel.playNext(context) },
                        enabled = viewModel.hasNextItem,
                        modifier = Modifier.offset(x = 12.dp).alpha(if (viewModel.hasNextItem) 1f else 0.3f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = stringResource(R.string.player_next),
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Slider(
                    value = if (isDragging) dragPosition else (if (duration > 0) position.toFloat() / duration else 0f),
                    onValueChange = { 
                        isDragging = true
                        dragPosition = it 
                    },
                    onValueChangeFinished = {
                        val newPos = (dragPosition * duration).toLong()
                        PlaybackManager.seekTo(newPos)
                        position = newPos
                        isDragging = false
                    },
                    modifier = Modifier.fillMaxWidth(),
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
                    val displayPosition = if (isDragging) (dragPosition * duration).toLong() else position
                    val chapters by viewModel.chapters.collectAsState()
                    val currentChapter = remember(chapters, displayPosition) {
                        chapters.find { displayPosition >= (it.start * 1000) && displayPosition < ((it.start + it.duration) * 1000) }
                    }
                    
                    val leftLabel = if (viewModel.useChapterContext && currentChapter != null) {
                        val chapterPos = displayPosition - (currentChapter.start * 1000).toLong()
                        formatTime(chapterPos.coerceAtLeast(0))
                    } else {
                        formatTime(displayPosition)
                    }

                    val rightLabel = if (viewModel.useChapterContext && currentChapter != null) {
                        val chapterDuration = (currentChapter.duration * 1000).toLong()
                        val chapterPos = displayPosition - (currentChapter.start * 1000).toLong()
                        if (viewModel.useRemainingTime) {
                            "-${formatTime((chapterDuration - chapterPos).coerceAtLeast(0))}"
                        } else {
                            formatTime(chapterDuration)
                        }
                    } else {
                        if (viewModel.useRemainingTime) {
                            "-${formatTime((duration - displayPosition).coerceAtLeast(0))}"
                        } else {
                            formatTime(duration)
                        }
                    }

                    Text(
                        text = leftLabel,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(72.dp) // Increased width slightly for larger text
                    )
                    Text(
                        text = if (currentChapter != null) currentChapter.title else stringResource(R.string.player_chapter_default),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                        maxLines = 1
                    )
                    Text(
                        text = rightLabel,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(72.dp) // Increased width slightly
                    )
                }

                Spacer(modifier = Modifier.height(48.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SeekButton(
                        isForward = false,
                        seconds = viewModel.rewindInterval
                    ) { viewModel.seekBackward() }

                    IconButton(
                        onClick = { viewModel.togglePlayPause() },
                        modifier = Modifier.size(100.dp) // Increased from 80dp
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = stringResource(R.string.player_play_pause),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    SeekButton(
                        isForward = true,
                        seconds = viewModel.forwardInterval
                    ) { viewModel.seekForward() }
                }

                Spacer(modifier = Modifier.weight(1f))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
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
                        onClick = { 
                            if (viewModel.listButtonOpens == "Chapters") {
                                viewModel.showChaptersList = true 
                            } else {
                                viewModel.showBookmarksList = true
                            }
                        }
                    )
                    PlayerBottomButton(
                        icon = Icons.Default.MoreHoriz,
                        onClick = { viewModel.toggleMoreOptions() }
                    )
                }
            }
        }
    }
}

@Composable
fun SheetHeaderButton(
    text: String,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(
            containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
            contentColor = MaterialTheme.colorScheme.primary
        ),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        modifier = Modifier.height(36.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
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
                    text = stringResource(R.string.player_chapters_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                SheetHeaderButton(text = stringResource(R.string.common_done), onClick = onDismiss)
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
                                    text = stringResource(
                                        R.string.player_chapter_details,
                                        formatTime((chapter.start * 1000).toLong()),
                                        formatTime((chapter.duration * 1000).toLong())
                                    ),
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
fun MoreOptionsSheet(
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
            BookmarkDialogButton(text = stringResource(R.string.player_chapters_title)) {
                viewModel.showMoreOptions = false
                viewModel.showChaptersList = true
            }
            BookmarkDialogButton(text = stringResource(R.string.player_jump_to_start)) {
                viewModel.jumpToStart()
            }
            BookmarkDialogButton(text = viewModel.currentItem?.let { if (it.isFinished) stringResource(R.string.player_mark_as_unfinished) else stringResource(R.string.player_mark_as_finished) } ?: stringResource(R.string.player_mark_as_finished)) {
                viewModel.toggleFinished()
            }
            BookmarkDialogButton(text = if (viewModel.isRepeatEnabled) stringResource(R.string.player_repeat_off) else stringResource(R.string.player_repeat_on)) {
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
                text = if (isExisting) stringResource(R.string.player_bookmark_exists, formatTime((time * 1000).toLong())) 
                       else stringResource(R.string.player_bookmark_saved, formatTime((time * 1000).toLong())),
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
                    BookmarkDialogButton(text = stringResource(R.string.player_add_note), onClick = onAddNote)
                }
                BookmarkDialogButton(text = stringResource(R.string.player_see_bookmarks), onClick = onSeeBookmarks)
                BookmarkDialogButton(text = stringResource(R.string.common_ok), onClick = onDismiss)
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
                stringResource(R.string.player_add_note_title),
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
                    placeholder = { Text(stringResource(R.string.player_note_placeholder)) },
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
                        Text(stringResource(R.string.common_cancel), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
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
                        Text(stringResource(R.string.common_ok), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
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
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close), tint = MaterialTheme.colorScheme.primary)
                }
                Text(
                    text = stringResource(R.string.player_bookmarks_title),
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
                SheetHeaderButton(text = "More", onClick = onMoreClick)
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
                Text(stringResource(R.string.player_speed_min), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Text(stringResource(R.string.player_speed_max), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
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
                QuickSpeedLabelButton(formatSpeed(viewModel.quickAction1), currentSpeed == viewModel.quickAction1) {
                    currentSpeed = viewModel.quickAction1
                    viewModel.setPlaybackSpeed(context, viewModel.quickAction1)
                }
                QuickSpeedLabelButton(formatSpeed(viewModel.quickAction2), currentSpeed == viewModel.quickAction2) {
                    currentSpeed = viewModel.quickAction2
                    viewModel.setPlaybackSpeed(context, viewModel.quickAction2)
                }
                QuickSpeedLabelButton(formatSpeed(viewModel.quickAction3), currentSpeed == viewModel.quickAction3) {
                    currentSpeed = viewModel.quickAction3
                    viewModel.setPlaybackSpeed(context, viewModel.quickAction3)
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
                        text = if (label == "hours") value.toString() else (if (value < 10) "0$value" else value.toString()),
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
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close), tint = MaterialTheme.colorScheme.primary)
                }
                Text(
                    text = stringResource(R.string.player_sleep_timer_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Box(modifier = Modifier.size(40.dp))
            }

            Spacer(modifier = Modifier.height(32.dp))

            val options = listOf(
                stringResource(R.string.player_timer_off) to 0,
                stringResource(R.string.player_timer_5m) to 5,
                stringResource(R.string.player_timer_10m) to 10,
                stringResource(R.string.player_timer_15m) to 15,
                stringResource(R.string.player_timer_30m) to 30,
                stringResource(R.string.player_timer_45m) to 45,
                stringResource(R.string.player_timer_1h) to 60,
                stringResource(R.string.player_timer_end_chapter) to -1
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                options.forEach { (label, minutes) ->
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
                
                BookmarkDialogButton(text = stringResource(R.string.player_timer_custom)) {
                    viewModel.toggleSleepTimerMenu()
                    viewModel.toggleCustomSleepTimerPicker()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtendedControlsSheet(
    viewModel: PlayerViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current.applicationContext
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    
    var showRewindPicker by remember { mutableStateOf(false) }
    var showForwardPicker by remember { mutableStateOf(false) }
    var showSmartRewindPicker by remember { mutableStateOf(false) }
    var showListActionPicker by remember { mutableStateOf(false) }
    var showSpeedPicker1 by remember { mutableStateOf(false) }
    var showSpeedPicker2 by remember { mutableStateOf(false) }
    var showSpeedPicker3 by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.loadSettings(context)
    }

    if (showRewindPicker) {
        IntervalPickerDialog(
            title = "Rewind Interval",
            currentValue = viewModel.rewindInterval,
            onValueSelected = {
                viewModel.updateRewindInterval(context, it)
                showRewindPicker = false
            },
            onDismiss = { showRewindPicker = false }
        )
    }

    if (showForwardPicker) {
        IntervalPickerDialog(
            title = "Forward Interval",
            currentValue = viewModel.forwardInterval,
            onValueSelected = {
                viewModel.updateForwardInterval(context, it)
                showForwardPicker = false
            },
            onDismiss = { showForwardPicker = false }
        )
    }

    if (showSmartRewindPicker) {
        IntervalPickerDialog(
            title = "Smart Rewind Limit",
            currentValue = viewModel.smartRewindLimit,
            onValueSelected = {
                viewModel.updateSmartRewindLimit(context, it)
                showSmartRewindPicker = false
            },
            onDismiss = { showSmartRewindPicker = false }
        )
    }

    if (showListActionPicker) {
        OptionsPickerDialog(
            title = "List Button Action",
            options = listOf("Chapters", "Bookmarks"),
            currentValue = viewModel.listButtonOpens,
            onValueSelected = {
                viewModel.updateListButtonOpens(context, it)
                showListActionPicker = false
            },
            onDismiss = { showListActionPicker = false }
        )
    }

    if (showSpeedPicker1) {
        SpeedPickerDialog(
            title = "Quick Action 1",
            currentValue = viewModel.quickAction1,
            onValueSelected = {
                viewModel.updateQuickAction1(context, it)
                showSpeedPicker1 = false
            },
            onDismiss = { showSpeedPicker1 = false }
        )
    }

    if (showSpeedPicker2) {
        SpeedPickerDialog(
            title = stringResource(R.string.player_quick_action_2),
            currentValue = viewModel.quickAction2,
            onValueSelected = {
                viewModel.updateQuickAction2(context, it)
                showSpeedPicker2 = false
            },
            onDismiss = { showSpeedPicker2 = false }
        )
    }

    if (showSpeedPicker3) {
        SpeedPickerDialog(
            title = "Quick Action 3",
            currentValue = viewModel.quickAction3,
            onValueSelected = {
                viewModel.updateQuickAction3(context, it)
                showSpeedPicker3 = false
            },
            onDismiss = { showSpeedPicker3 = false }
        )
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
                    SettingsRowPicker("Rewind", formatInterval(context, 
viewModel.rewindInterval)) { showRewindPicker = true }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    SettingsRowPicker("Forward", formatInterval(context, 
viewModel.forwardInterval)) { showForwardPicker = true }
                }
            }
            Text(
                "Adjust the amount skipped when using the buttons in the Player or Control Center.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 8.dp, start = 8.dp, bottom = 16.dp)
            )

            Surface(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column {
                    SettingsRowToggle("Smart Rewind", viewModel.smartRewind) {
                        viewModel.updateSmartRewind(context, it)
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    SettingsRowPicker("Smart Rewind Limit", formatInterval(context, 
viewModel.smartRewindLimit)) { showSmartRewindPicker = true }
                }
            }
            Text(
                stringResource(R.string.player_settings_smart_rewind_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 8.dp, start = 8.dp, bottom = 16.dp)
            )

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
                modifier = Modifier.padding(top = 8.dp, start = 8.dp, bottom = 16.dp)
            )

            Surface(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                shape = RoundedCornerShape(16.dp)
            ) {
                SettingsRowToggle(stringResource(R.string.player_boost_volume), viewModel.volumeBoost) {
                    viewModel.toggleVolumeBoost(context)
                }
            }
            Text(
                "Doubles the volume.\nUse with caution and care for your hearing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 8.dp, start = 8.dp, bottom = 16.dp)
            )

            SettingsSectionLabel("Speed")
            Surface(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column {
                    SettingsRowPicker(stringResource(R.string.player_quick_action_1), formatSpeed(viewModel.quickAction1)) { showSpeedPicker1 = true }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    SettingsRowPicker(stringResource(R.string.player_quick_action_2), formatSpeed(viewModel.quickAction2)) { showSpeedPicker2 = true }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    SettingsRowPicker(stringResource(R.string.player_quick_action_3), formatSpeed(viewModel.quickAction3)) { showSpeedPicker3 = true }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    SettingsRowToggle(stringResource(R.string.player_settings_global_speed), viewModel.globalSpeed) {
                        viewModel.updateGlobalSpeed(context, it)
                    }
                }
            }
            Text(
                "Set speed across all books.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 8.dp, start = 8.dp, bottom = 16.dp)
            )

            Surface(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                shape = RoundedCornerShape(16.dp)
            ) {
                SettingsRowToggle("Progress Bar Seeking", viewModel.progressBarSeeking) {
                    viewModel.updateProgressBarSeeking(context, it)
                }
            }
            Text(
                "Enable seeking on the lock screen",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 8.dp, start = 8.dp, bottom = 16.dp)
            )

            Surface(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                shape = RoundedCornerShape(16.dp)
            ) {
                SettingsRowPicker(stringResource(R.string.player_settings_list_opens), viewModel.listButtonOpens) { showListActionPicker = true }
            }
            Text(
                "Adjust what the list button in the player screen opens",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 8.dp, start = 8.dp, bottom = 16.dp)
            )

            SettingsSectionLabel(stringResource(R.string.player_settings_progress_labels))
            Surface(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column {
                    SettingsRowToggle(stringResource(R.string.player_settings_use_remaining_time), viewModel.useRemainingTime) {
                        viewModel.updateUseRemainingTime(context, it)
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    SettingsRowToggle(stringResource(R.string.player_settings_use_chapter_context), viewModel.useChapterContext) {
                        viewModel.updateUseChapterContext(context, it)
                    }
                }
            }
            Text(
                stringResource(R.string.player_settings_progress_labels_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 8.dp, start = 8.dp, bottom = 32.dp)
            )
        }
    }
}

@Composable
fun IntervalPickerDialog(
    title: String,
    currentValue: Int,
    onValueSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val options = listOf(
        2, 5, 10, 15, 20, 30, 45, 60, 90, 120, 180, 240
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
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
                    .verticalScroll(rememberScrollState())
            ) {
                options.forEach { seconds ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onValueSelected(seconds) }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = formatInterval(context, seconds),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (seconds == currentValue) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    if (seconds != options.last()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(28.dp)
    )
}

@Composable
fun OptionsPickerDialog(
    title: String,
    options: List<String>,
    currentValue: String,
    onValueSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onValueSelected(option) }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = option,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (option == currentValue) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    if (option != options.last()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(28.dp)
    )
}

@Composable
fun SpeedPickerDialog(
    title: String,
    currentValue: Float,
    onValueSelected: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    // Generate speed options from 0.5 to 4.0 in 0.05 steps
    val options = remember {
        (10..80).map { it * 0.05f }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
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
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                options.forEach { speed ->
                    val isSelected = kotlin.math.abs(speed - currentValue) < 0.01f
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onValueSelected(speed) }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = formatSpeed(speed),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    if (speed != options.last()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(28.dp)
    )
}

private fun formatSpeed(speed: Float): String {
    val s = "%.2f".format(speed).trimEnd('0').trimEnd('.')
    return "${s}x"
}

private fun formatInterval(context: android.content.Context, seconds: Int): String {
    return when {
        seconds < 60 -> context.getString(R.string.interval_seconds, seconds)
        seconds == 60 -> context.getString(R.string.interval_1_min)
        seconds == 90 -> context.getString(R.string.interval_1_min_30_secs)
        else -> context.getString(R.string.interval_minutes, seconds / 60)
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
fun SeekButton(isForward: Boolean, seconds: Int, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(CircleShape)
            .clickable { onClick() }
            .padding(8.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (isForward) Icons.AutoMirrored.Filled.RotateRight else Icons.AutoMirrored.Filled.RotateLeft,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(72.dp) // Increased from 60dp
            )
            Text(
                text = "$seconds",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp // Slightly increased from 12sp
                ),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp) // Optical alignment
            )
        }
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
    
    val mStr = if (minutes < 10) "0$minutes" else minutes.toString()
    val sStr = if (seconds < 10) "0$seconds" else seconds.toString()
    
    return "$mStr:$sStr"
}