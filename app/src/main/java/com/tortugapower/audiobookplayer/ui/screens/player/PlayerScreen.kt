package com.tortugapower.audiobookplayer.ui.screens.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.Path
import androidx.compose.foundation.Canvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.media3.common.Player
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.logic.PlaybackManager
import com.tortugapower.audiobookplayer.viewmodel.PlayerViewModel
import com.tortugapower.audiobookplayer.ui.components.BookPlayerSlider
import androidx.compose.animation.core.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.random.Random

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
    val currentItem = viewModel.currentItem.collectAsStateWithLifecycle().value
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val playbackSpeed by viewModel.playbackSpeed.collectAsStateWithLifecycle()
    val isTransitioning by viewModel.isTransitioning.collectAsStateWithLifecycle()
    val showPlayerScreen by PlaybackManager.showPlayerScreen.collectAsStateWithLifecycle()
    val playPauseFocusRequester = remember { FocusRequester() }
    
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

    LaunchedEffect(showPlayerScreen, screenHeightPx) {
        if (showPlayerScreen) {
            offsetY.animateTo(0f, tween(400))
            try {
                playPauseFocusRequester.requestFocus()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            // Ensure it's completely off-screen by adding a buffer
            offsetY.animateTo(screenHeightPx + 500f, tween(300))
        }
    }

    // Position comes from PlaybackManager.positionMs: it ticks while playing and is re-seeded on seek
    // (onPositionDiscontinuity) and on load. We collect it lifecycle-aware, so the UI no longer polls
    // the player or races its post-seek settle (the old 30-iteration loop is gone). For BOUND books,
    // add the current chapter's cumulative start to the raw per-item position.
    val rawPositionMs by PlaybackManager.positionMs.collectAsStateWithLifecycle()
    LaunchedEffect(rawPositionMs, currentItem?.uuid, isDragging, isTransitioning, viewModel.seekTrigger) {
        if (isDragging || isTransitioning) return@LaunchedEffect
        val p = viewModel.player ?: return@LaunchedEffect
        if (p.playbackState != Player.STATE_READY && p.playbackState != Player.STATE_BUFFERING) return@LaunchedEffect
        position = if (currentItem?.type == com.tortugapower.audiobookplayer.database.entities.ItemType.BOUND) {
            val chapters = viewModel.chapters.value
            val idx = p.currentMediaItemIndex
            if (idx >= 0 && idx < chapters.size) (chapters[idx].start * 1000).toLong() + rawPositionMs
            else rawPositionMs
        } else {
            rawPositionMs
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
            absolutePosition = position,
            onDismiss = { viewModel.showChaptersList = false }
        )
    }

    val paneTitleText = stringResource(R.string.player_pane_title)
    if (showPlayerScreen || offsetY.value < screenHeightPx) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(0, offsetY.value.roundToInt()) }
                .graphicsLayer { 
                    alpha = if (isHidden) 0f else 1f
                }
                .background(MaterialTheme.colorScheme.background)
                .pointerInput(isHidden) {
                    if (!isHidden) {
                        detectTapGestures(onTap = { })
                    }
                }
                .semantics {
                    if (!isHidden) {
                        paneTitle = paneTitleText
                    }
                }
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
                                        offsetY.animateTo(screenHeightPx + 500f, tween(300))
                                        PlaybackManager.setShowPlayer(false)
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
            val chapters by viewModel.chapters.collectAsState()
            val currentChapterIndex = remember(chapters, position, isDragging, dragPosition, viewModel.useChapterContext) {
                val pos = if (isDragging && !viewModel.useChapterContext) {
                    (dragPosition * duration).toLong()
                } else {
                    position
                }
                chapters.indexOfFirst { pos >= (it.start * 1000) && pos < ((it.start + it.duration) * 1000) }
            }
            val currentChapter = chapters.getOrNull(currentChapterIndex)

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

                val isLocal = remember(currentItem.relativePath, currentItem.type) {
                    if (currentItem.type == com.tortugapower.audiobookplayer.database.entities.ItemType.FOLDER) true
                    else if (currentItem.relativePath == null) false
                    else {
                        val processedDir = java.io.File(context.filesDir, "Processed")
                        java.io.File(processedDir, currentItem.relativePath!!).exists()
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(24.dp))
                        .then(artworkBackground),
                    contentAlignment = Alignment.Center // Changed from TopEnd to Center
                ) {
                    if (currentItem.artworkURL != null) {
                        AsyncImage(
                            model = currentItem.artworkURL,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }

                    if (playbackState == Player.STATE_BUFFERING && !isLocal) {
                        SoundwaveLoadingOverlay()
                    }

                    if (!isLocal && !currentItem.remoteURL.isNullOrEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val path = Path().apply {
                                    moveTo(size.width, size.height * 0.8f)
                                    lineTo(size.width, size.height)
                                    lineTo(size.width * 0.8f, size.height)
                                    close()
                                }
                                drawPath(path, Color.Black.copy(alpha = 0.65f))
                            }
                            Icon(
                                imageVector = Icons.Outlined.Cloud,
                                contentDescription = null,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(8.dp)
                                    .size(24.dp),
                                tint = Color(0xFF4285F4)
                            )
                        }
                    }
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
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
                }

                Spacer(modifier = Modifier.height(32.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // In chapter context, we can always go back (to start of chapter or previous chapter/item)
                    // if hasPreviousItem is true OR we are in a volume/book with chapters.
                    val canGoBack = viewModel.hasPreviousItem || (viewModel.useChapterContext && chapters.isNotEmpty())
                    val canGoForward = viewModel.hasNextItem || (viewModel.useChapterContext && chapters.isNotEmpty())

                    IconButton(
                        onClick = { viewModel.playPrevious(context) },
                        enabled = canGoBack,
                        modifier = Modifier.offset(x = (-12).dp).alpha(if (canGoBack) 1f else 0.3f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ChevronLeft,
                            contentDescription = stringResource(R.string.player_prev),
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    MarqueeText(
                        text = if (viewModel.useChapterContext && currentChapter != null) currentChapter.title else currentItem.title,
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
                        enabled = canGoForward,
                        modifier = Modifier.offset(x = 12.dp).alpha(if (canGoForward) 1f else 0.3f)
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

                // Announce "elapsed of total" to TalkBack when the seek bar is focused/scrubbed —
                // otherwise the slider only reports a bare percentage with no time context. Match the
                // visible labels: chapter-relative when chapter context is on, else the whole book.
                val seekStateDescription = if (viewModel.useChapterContext && currentChapter != null) {
                    val chapterPos = (position - (currentChapter.start * 1000).toLong()).coerceAtLeast(0)
                    stringResource(
                        R.string.player_seek_position,
                        formatTime(chapterPos),
                        formatTime((currentChapter.duration * 1000).toLong())
                    )
                } else {
                    stringResource(R.string.player_seek_position, formatTime(position), formatTime(duration))
                }
                BookPlayerSlider(
                    modifier = Modifier.semantics { stateDescription = seekStateDescription },
                    value = if (isDragging) {
                        dragPosition
                    } else {
                        if (viewModel.useChapterContext && currentChapter != null) {
                            val chapterPos = position - (currentChapter.start * 1000).toLong()
                            val chapterDur = (currentChapter.duration * 1000).toLong()
                            if (chapterDur > 0) (chapterPos.toFloat() / chapterDur).coerceIn(0f, 1f) else 0f
                        } else {
                            if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                        }
                    },
                    onValueChange = { 
                        isDragging = true
                        dragPosition = it 
                    },
                    onValueChangeFinished = {
                        val newPos = if (viewModel.useChapterContext && currentChapter != null) {
                            (currentChapter.start * 1000).toLong() + (dragPosition * currentChapter.duration * 1000).toLong()
                        } else {
                            (dragPosition * duration).toLong()
                        }
                        viewModel.seekToAbsolute(newPos)
                        position = newPos
                        isDragging = false
                    }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val displayPosition = if (isDragging) {
                        if (viewModel.useChapterContext && currentChapter != null) {
                            (currentChapter.start * 1000).toLong() + (dragPosition * currentChapter.duration * 1000).toLong()
                        } else {
                            (dragPosition * duration).toLong()
                        }
                    } else position

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
                        modifier = Modifier.width(72.dp)
                    )
                    
                    val centerLabel = if (viewModel.useChapterContext && chapters.isNotEmpty()) {
                        if (currentChapterIndex != -1) {
                            stringResource(R.string.player_chapter_progress, currentChapterIndex + 1, chapters.size)
                        } else {
                            stringResource(R.string.player_chapter_default)
                        }
                    } else {
                        val percent = if (duration > 0) (displayPosition.toDouble() / duration * 100).toInt() else 0
                        "$percent%"
                    }

                    Text(
                        text = centerLabel,
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
                        modifier = Modifier.width(72.dp)
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
                        modifier = Modifier
                            .size(100.dp) // Increased from 80dp
                            .focusRequester(playPauseFocusRequester)
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
                        label = "${if (playbackSpeed % 1.0f == 0.0f) playbackSpeed.toInt() else playbackSpeed}x",
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
    absolutePosition: Long,
    onDismiss: () -> Unit
) {
    val chapters by viewModel.chapters.collectAsState()
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
                    val isPlaying = absolutePosition >= (chapter.start * 1000) && 
                                    absolutePosition < ((chapter.start + chapter.duration) * 1000)
                    
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
    
    val currentItem by viewModel.currentItem.collectAsStateWithLifecycle()
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
            BookmarkDialogButton(text = currentItem?.let { if (it.isFinished) stringResource(R.string.player_mark_as_unfinished) else stringResource(R.string.player_mark_as_finished) } ?: stringResource(R.string.player_mark_as_finished)) {
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
                stringResource(R.string.player_bookmarks_manual),
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
    var currentSpeed by remember { mutableStateOf(viewModel.playbackSpeed.value) }
    var currentVolume by remember { mutableStateOf(viewModel.playbackVolume.value) }
    val volumeBoost by viewModel.volumeBoost.collectAsStateWithLifecycle()
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
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close), tint = MaterialTheme.colorScheme.primary)
                }
                Text(
                    text = stringResource(R.string.player_controls_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                SheetHeaderButton(text = stringResource(R.string.common_more), onClick = onMoreClick)
            }

            Spacer(modifier = Modifier.height(32.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.player_set_speed), style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${if (currentSpeed % 1.0f == 0.0f) currentSpeed.toInt() else currentSpeed}x",
                    fontWeight = FontWeight.Bold
                )
            }
            
            BookPlayerSlider(
                value = currentSpeed,
                onValueChange = { 
                    currentSpeed = it
                    viewModel.setPlaybackSpeed(context, it)
                },
                valueRange = 0.5f..4.0f
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

            Text(stringResource(R.string.player_volume), style = MaterialTheme.typography.bodyLarge)
            BookPlayerSlider(
                value = currentVolume,
                onValueChange = { 
                    currentVolume = it
                    viewModel.setPlaybackVolume(context, it)
                },
                valueRange = 0.0f..1.0f
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.player_boost_volume), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.player_boost_volume_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Switch(
                    checked = volumeBoost,
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
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close), tint = MaterialTheme.colorScheme.primary)
                }
                Text(
                    text = stringResource(R.string.player_custom_timer),
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
                    label = stringResource(R.string.player_timer_hours),
                    padToTwoDigits = false,
                    modifier = Modifier.weight(1f)
                )
                TimeWheelPicker(
                    range = 0..59,
                    selectedValue = selectedMinutes,
                    onValueChange = { selectedMinutes = it },
                    label = stringResource(R.string.player_timer_min),
                    padToTwoDigits = true,
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
                Text(stringResource(R.string.player_start_timer), style = MaterialTheme.typography.titleMedium)
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
    padToTwoDigits: Boolean = false,
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
                        text = if (!padToTwoDigits) value.toString() else (if (value < 10) "0$value" else value.toString()),
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
    val volumeBoost by viewModel.volumeBoost.collectAsStateWithLifecycle()

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
            title = stringResource(R.string.player_rewind_interval_title),
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
            title = stringResource(R.string.player_forward_interval_title),
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
            title = stringResource(R.string.player_smart_rewind_limit_title),
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
            title = stringResource(R.string.player_list_button_action_title),
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
            title = stringResource(R.string.player_quick_action_1),
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
            title = stringResource(R.string.player_quick_action_3),
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
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close), tint = MaterialTheme.colorScheme.primary)
                }
                Text(
                    text = stringResource(R.string.player_controls_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Box(modifier = Modifier.size(40.dp))
            }

            Spacer(modifier = Modifier.height(24.dp))

            SettingsSectionLabel(stringResource(R.string.player_settings_skip_intervals))
            Surface(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column {
                    SettingsRowPicker(stringResource(R.string.player_settings_rewind), formatInterval(context,
viewModel.rewindInterval)) { showRewindPicker = true }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    SettingsRowPicker(stringResource(R.string.player_settings_forward), formatInterval(context,
viewModel.forwardInterval)) { showForwardPicker = true }
                }
            }
            Text(
                stringResource(R.string.player_settings_skip_intervals_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 8.dp, start = 8.dp, bottom = 16.dp)
            )

            Surface(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column {
                    SettingsRowToggle(stringResource(R.string.player_settings_smart_rewind), viewModel.smartRewind) {
                        viewModel.updateSmartRewind(context, it)
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    SettingsRowPicker(stringResource(R.string.player_settings_smart_rewind_limit), formatInterval(context,
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
                SettingsRowToggle(stringResource(R.string.player_settings_auto_sleep_timer), viewModel.autoSleep) {
                    viewModel.updateAutoSleep(context, it)
                }
            }
            Text(
                stringResource(R.string.player_settings_auto_sleep_timer_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 8.dp, start = 8.dp, bottom = 16.dp)
            )

            Surface(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                shape = RoundedCornerShape(16.dp)
            ) {
                SettingsRowToggle(stringResource(R.string.player_boost_volume), volumeBoost) {
                    viewModel.toggleVolumeBoost(context)
                }
            }
            Text(
                stringResource(R.string.player_boost_volume_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 8.dp, start = 8.dp, bottom = 16.dp)
            )

            SettingsSectionLabel(stringResource(R.string.player_settings_speed))
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
                stringResource(R.string.player_settings_global_speed_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 8.dp, start = 8.dp, bottom = 16.dp)
            )

            Surface(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                shape = RoundedCornerShape(16.dp)
            ) {
                SettingsRowToggle(stringResource(R.string.player_settings_progress_bar_seeking), viewModel.progressBarSeeking) {
                    viewModel.updateProgressBarSeeking(context, it)
                }
            }
            Text(
                stringResource(R.string.player_settings_progress_bar_seeking_desc),
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
                stringResource(R.string.player_settings_list_opens_desc),
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
                                contentDescription = stringResource(R.string.common_selected),
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
                                contentDescription = stringResource(R.string.common_selected),
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
                                contentDescription = stringResource(R.string.common_selected),
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

@Composable
fun SoundwaveLoadingOverlay() {
    val infiniteTransition = rememberInfiniteTransition(label = "loading")
    
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    // Trigger height change when alpha is at its maximum (darkest point)
    val seed = remember(alpha > 0.79f) { kotlin.random.Random.nextInt() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = alpha)),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.height(40.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(10) { i ->
                // Animate height based on seed and index
                val heightScale by animateFloatAsState(
                    targetValue = remember(seed, i) { 0.2f + kotlin.random.Random.nextFloat() * 0.8f },
                    animationSpec = tween(500), // Smooth transition when seed changes
                    label = "height_$i"
                )
                
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight(heightScale)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
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