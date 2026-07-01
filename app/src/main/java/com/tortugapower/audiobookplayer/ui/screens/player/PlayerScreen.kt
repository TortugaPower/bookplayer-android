package com.tortugapower.audiobookplayer.ui.screens.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.database.entities.ChapterEntity
import com.tortugapower.audiobookplayer.logic.BoundTimeline
import com.tortugapower.audiobookplayer.logic.PlaybackManager
import com.tortugapower.audiobookplayer.ui.components.BookPlayerSlider
import com.tortugapower.audiobookplayer.viewmodel.PlayerViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
            BoundTimeline.of(viewModel.chapters.value).toAbsoluteMs(p.currentMediaItemIndex, rawPositionMs)
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
            // derivedStateOf so the chapter scan only re-runs (and readers only recompose) when the
            // computed index actually changes — not on every ~500ms position tick. Keyed on `duration`
            // since that's the only non-State input; position/isDragging/dragPosition/useChapterContext/
            // chapters are State and tracked automatically.
            val currentChapterIndex by remember(duration) {
                derivedStateOf {
                    val pos = if (isDragging && !viewModel.useChapterContext) {
                        (dragPosition * duration).toLong()
                    } else {
                        position
                    }
                    BoundTimeline.of(chapters).indexAt(pos)
                }
            }
            val currentChapter = chapters.getOrNull(currentChapterIndex)

            // isLocal is computed off the main thread in the ViewModel (no File.exists in composition).
            val isLocal by viewModel.isCurrentItemLocal.collectAsStateWithLifecycle()

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

                PlayerArtwork(
                    artworkURL = currentItem.artworkURL,
                    isBuffering = playbackState == Player.STATE_BUFFERING && !isLocal,
                    showCloudBadge = !isLocal && !currentItem.remoteURL.isNullOrEmpty()
                )

                Spacer(modifier = Modifier.height(32.dp))

                // In chapter context, we can always go back (to start of chapter or previous chapter/item)
                // if hasPreviousItem is true OR we are in a volume/book with chapters.
                val canGoBack = viewModel.hasPreviousItem || (viewModel.useChapterContext && chapters.isNotEmpty())
                val canGoForward = viewModel.hasNextItem || (viewModel.useChapterContext && chapters.isNotEmpty())

                PlayerTitleNavRow(
                    title = if (viewModel.useChapterContext && currentChapter != null) currentChapter.title else currentItem.title,
                    canGoBack = canGoBack,
                    canGoForward = canGoForward,
                    onPrevious = { viewModel.playPrevious(context) },
                    onNext = { viewModel.playNext(context) }
                )

                Spacer(modifier = Modifier.height(24.dp))

                PlayerProgressSection(
                    state = PlayerProgressUiState(
                        position = position,
                        duration = duration,
                        isDragging = isDragging,
                        dragPosition = dragPosition,
                        currentChapter = currentChapter,
                        currentChapterIndex = currentChapterIndex,
                        chaptersCount = chapters.size,
                        useChapterContext = viewModel.useChapterContext,
                        useRemainingTime = viewModel.useRemainingTime,
                    ),
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

                Spacer(modifier = Modifier.height(48.dp))

                PlayerTransportControls(
                    isPlaying = isPlaying,
                    rewindInterval = viewModel.rewindInterval,
                    forwardInterval = viewModel.forwardInterval,
                    playPauseFocusRequester = playPauseFocusRequester,
                    onRewind = { viewModel.seekBackward() },
                    onPlayPause = { viewModel.togglePlayPause() },
                    onForward = { viewModel.seekForward() }
                )

                Spacer(modifier = Modifier.weight(1f))

                PlayerBottomBar(
                    speedLabel = "${if (playbackSpeed % 1.0f == 0.0f) playbackSpeed.toInt() else playbackSpeed}x",
                    sleepLabel = if (viewModel.sleepTimerActive) viewModel.sleepTimerRemaining else null,
                    onSpeed = { viewModel.toggleControlsSheet() },
                    onSleep = { viewModel.toggleSleepTimerMenu() },
                    onBookmark = { viewModel.addBookmark() },
                    onList = {
                        if (viewModel.listButtonOpens == "Chapters") {
                            viewModel.showChaptersList = true
                        } else {
                            viewModel.showBookmarksList = true
                        }
                    },
                    onMore = { viewModel.toggleMoreOptions() }
                )
            }
        }
    }
}
}

@Composable
private fun PlayerArtwork(
    artworkURL: String?,
    isBuffering: Boolean,
    showCloudBadge: Boolean
) {
    val artworkBackground = if (artworkURL == null) {
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
        contentAlignment = Alignment.Center
    ) {
        if (artworkURL != null) {
            AsyncImage(
                model = artworkURL,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        if (isBuffering) {
            SoundwaveLoadingOverlay()
        }

        if (showCloudBadge) {
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
}

@Composable
private fun PlayerTitleNavRow(
    title: String,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onPrevious,
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
            text = title,
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
            onClick = onNext,
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
}

/**
 * Everything the progress UI renders, assembled by [PlayerScreen] when playback updates — the Android
 * analog of iOS's progress object. Bundled to keep [PlayerProgressSection]'s call site small.
 */
private data class PlayerProgressUiState(
    val position: Long,
    val duration: Long,
    val isDragging: Boolean,
    val dragPosition: Float,
    val currentChapter: ChapterEntity?,
    val currentChapterIndex: Int,
    val chaptersCount: Int,
    val useChapterContext: Boolean,
    val useRemainingTime: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerProgressSection(
    state: PlayerProgressUiState,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    val position = state.position
    val duration = state.duration
    val isDragging = state.isDragging
    val dragPosition = state.dragPosition
    val currentChapter = state.currentChapter
    val currentChapterIndex = state.currentChapterIndex
    val chaptersCount = state.chaptersCount
    val useChapterContext = state.useChapterContext
    val useRemainingTime = state.useRemainingTime
    // Announce "elapsed of total" to TalkBack when the seek bar is focused/scrubbed —
    // otherwise the slider only reports a bare percentage with no time context. Match the
    // visible labels: chapter-relative when chapter context is on, else the whole book.
    val seekStateDescription = if (useChapterContext && currentChapter != null) {
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
            if (useChapterContext && currentChapter != null) {
                val chapterPos = position - (currentChapter.start * 1000).toLong()
                val chapterDur = (currentChapter.duration * 1000).toLong()
                if (chapterDur > 0) (chapterPos.toFloat() / chapterDur).coerceIn(0f, 1f) else 0f
            } else {
                if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
            }
        },
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        val displayPosition = if (isDragging) {
            if (useChapterContext && currentChapter != null) {
                (currentChapter.start * 1000).toLong() + (dragPosition * currentChapter.duration * 1000).toLong()
            } else {
                (dragPosition * duration).toLong()
            }
        } else position

        val leftLabel = if (useChapterContext && currentChapter != null) {
            val chapterPos = displayPosition - (currentChapter.start * 1000).toLong()
            formatTime(chapterPos.coerceAtLeast(0))
        } else {
            formatTime(displayPosition)
        }

        val rightLabel = if (useChapterContext && currentChapter != null) {
            val chapterDuration = (currentChapter.duration * 1000).toLong()
            val chapterPos = displayPosition - (currentChapter.start * 1000).toLong()
            if (useRemainingTime) {
                "-${formatTime((chapterDuration - chapterPos).coerceAtLeast(0))}"
            } else {
                formatTime(chapterDuration)
            }
        } else {
            if (useRemainingTime) {
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

        val centerLabel = if (useChapterContext && chaptersCount > 0) {
            if (currentChapterIndex != -1) {
                stringResource(R.string.player_chapter_progress, currentChapterIndex + 1, chaptersCount)
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
}

@Composable
private fun PlayerTransportControls(
    isPlaying: Boolean,
    rewindInterval: Int,
    forwardInterval: Int,
    playPauseFocusRequester: FocusRequester,
    onRewind: () -> Unit,
    onPlayPause: () -> Unit,
    onForward: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        SeekButton(
            isForward = false,
            seconds = rewindInterval
        ) { onRewind() }

        IconButton(
            onClick = onPlayPause,
            modifier = Modifier
                .size(100.dp) // Increased from 80dp
                .focusRequester(playPauseFocusRequester)
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                // Label by the action the tap performs, like iOS VoiceOver: "Pause" while playing, "Play" while paused.
                contentDescription = stringResource(if (isPlaying) R.string.player_pause else R.string.player_play),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxSize()
            )
        }

        SeekButton(
            isForward = true,
            seconds = forwardInterval
        ) { onForward() }
    }
}

@Composable
private fun PlayerBottomBar(
    speedLabel: String,
    sleepLabel: String?,
    onSpeed: () -> Unit,
    onSleep: () -> Unit,
    onBookmark: () -> Unit,
    onList: () -> Unit,
    onMore: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        PlayerBottomButton(
            label = speedLabel,
            onClick = onSpeed
        )
        PlayerBottomButton(
            icon = Icons.Default.NightsStay,
            label = sleepLabel,
            contentDescription = stringResource(R.string.player_sleep_timer_title),
            onClick = onSleep
        )
        PlayerBottomButton(
            icon = Icons.Default.BookmarkBorder,
            contentDescription = stringResource(R.string.player_add_bookmark),
            onClick = onBookmark
        )
        PlayerBottomButton(
            icon = Icons.AutoMirrored.Filled.List,
            contentDescription = stringResource(R.string.player_chapters_title),
            onClick = onList
        )
        PlayerBottomButton(
            icon = Icons.Default.MoreHoriz,
            contentDescription = stringResource(R.string.common_more),
            onClick = onMore
        )
    }
}
