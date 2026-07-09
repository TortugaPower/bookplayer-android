package com.tortugapower.audiobookplayer.ui.screens.player

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.filled.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.filled.KeyboardDoubleArrowRight
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Bookmarks
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
import android.app.Activity
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.delay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.platform.LocalView
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.C
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tortugapower.audiobookplayer.logic.ItemArtwork
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.database.entities.ChapterEntity
import com.tortugapower.audiobookplayer.logic.PlaybackManager
import com.tortugapower.audiobookplayer.logic.PlayerUiSignals
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

    // One-shot signal from a deep link / launcher "sleep" shortcut handled in MainActivity — open the
    // sleep-timer menu once. The conflated channel latches a pre-composition emit (cold start) yet is
    // consumed once, so a config-change recreation won't re-open the menu.
    LaunchedEffect(Unit) {
        PlayerUiSignals.openSleepTimer.collect {
            viewModel.showSleepTimerMenu = true
        }
    }

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

    // PlaybackManager.positionMs is already WHOLE-BOOK ms (PlaybackManager inverts the session window,
    // whatever mode it's in). It ticks while playing and is re-seeded on seek (onPositionDiscontinuity)
    // and on load. Collected lifecycle-aware, so the UI no longer polls the player or races its
    // post-seek settle (the old 30-iteration loop is gone).
    val livePositionMs by PlaybackManager.positionMs.collectAsStateWithLifecycle()
    LaunchedEffect(livePositionMs, isDragging, isTransitioning, viewModel.seekTrigger) {
        if (isDragging || isTransitioning) return@LaunchedEffect
        position = livePositionMs
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

    if (viewModel.showCastSheet) {
        CastOptionsSheet(
            viewModel = viewModel,
            onDismiss = { viewModel.toggleCastSheet() }
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
        // Hoisted above the sheet container: fullscreen changes the container's own modifiers
        // (drag-to-dismiss and system-bar padding are suspended while a video is fullscreen).
        // rememberSaveable so a rotation mid-video doesn't kick the user out of fullscreen.
        var isFullscreen by rememberSaveable { mutableStateOf(false) }
        val player = viewModel.player
        var hasVideo by remember { mutableStateOf(false) }
        DisposableEffect(player) {
            val listener = object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) {
                    hasVideo = tracks.hasPlayableVideo()
                }
            }
            player?.addListener(listener)
            hasVideo = player?.currentTracks?.hasPlayableVideo() == true
            onDispose {
                player?.removeListener(listener)
            }
        }
        // Fullscreen only means something while a video is showing; drop it when the item
        // changes to an audio book so the next video doesn't open straight into fullscreen.
        LaunchedEffect(hasVideo) { if (!hasVideo) isFullscreen = false }
        val fullscreenActive = isFullscreen && hasVideo

        // While the UI is stopped (screen off, app backgrounded) or the player sheet is gone,
        // nothing shows the frames — but the video track would stay selected and MediaCodec
        // would keep decoding in the background, a pure battery drain on an app built around
        // background listening. Disabling the track type stops video decode; audio continues,
        // and re-enabling re-selects the track and repaints the surface.
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(player, hasVideo, lifecycleOwner) {
            if (player == null || !hasVideo ||
                !player.isCommandAvailable(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)
            ) {
                return@DisposableEffect onDispose { }
            }
            fun setVideoDecodeEnabled(enabled: Boolean) {
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled)
                    .build()
            }
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> setVideoDecodeEnabled(true)
                    Lifecycle.Event.ON_STOP -> setVideoDecodeEnabled(false)
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            setVideoDecodeEnabled(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                // The sheet left composition (collapsed to the mini player or closed): keep
                // decode off until it comes back. Recomposing with a video re-enables above.
                setVideoDecodeEnabled(false)
            }
        }

        // Real fullscreen: hide the system bars (a swipe reveals them transiently) while a
        // video is fullscreen; restore them on exit or when leaving the screen entirely.
        val rootView = LocalView.current
        DisposableEffect(fullscreenActive) {
            val activity = generateSequence(rootView.context) { (it as? ContextWrapper)?.baseContext }
                .firstNotNullOfOrNull { it as? Activity }
            val insetsController = activity?.window?.let { WindowCompat.getInsetsController(it, rootView) }
            if (fullscreenActive && insetsController != null) {
                insetsController.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
            }
            onDispose {
                insetsController?.show(WindowInsetsCompat.Type.systemBars())
            }
        }

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
                    if (isHidden || fullscreenActive) {
                        // No touch interception when hidden; no drag-to-dismiss over a
                        // fullscreen video (a scrub-like swipe must not close the player).
                        Modifier
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
                .then(
                    if (fullscreenActive) {
                        Modifier // edge-to-edge beneath the hidden system bars
                    } else {
                        Modifier
                            .statusBarsPadding()
                            .navigationBarsPadding()
                    }
                )
        ) {
            if (currentItem != null) {
                // Single source of truth for chapters: the currentPlayable snapshot. The index (via the
                // tested BoundTimeline.indexAt / chapterIndexAt), the current chapter, and the per-file
                // artwork all read from THIS one snapshot, so they can't disagree during a track transition.
                val currentPlayable by viewModel.currentPlayable.collectAsStateWithLifecycle()
                val chapters = currentPlayable?.chapterEntities ?: emptyList()
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
                        // Reuse the tested chapter-layer lookup instead of re-implementing the half-open
                        // [start, end) scan + clamp here; whole-book positions work for embedded chapters too.
                        currentPlayable?.chapterIndexAt(pos) ?: -1
                    }
                }
                val currentChapter = chapters.getOrNull(currentChapterIndex)

                // Artwork follows the current chapter's backing file (per-file, matching iOS), falling back to
                // the book's. Per-file artwork lives on PlayableChapter — same snapshot, same index as above.
                val chapterArtworkURL = currentPlayable?.chapters?.getOrNull(currentChapterIndex)?.artworkURL

                // isLocal is computed off the main thread in the ViewModel (no File.exists in composition).
                val isLocal by viewModel.isCurrentItemLocal.collectAsStateWithLifecycle()

                if (fullscreenActive) {
                    BackHandler { isFullscreen = false }
                }

                // ONE PlayerArtwork call site for both modes. Two separate call sites would give
                // fullscreen its own remembered PlayerView, so every toggle re-inflated the view
                // and re-attached the shared controller's video surface (black-frame flash, plus
                // a dispose-order race that could leave the fresh surface cleared). A single slot
                // keeps the view, its surface, and the captured blur alive across toggles.
                Column(
                    modifier = if (fullscreenActive) {
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                    } else {
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 32.dp)
                    },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (!fullscreenActive) {
                        Box(
                            modifier = Modifier
                                .padding(vertical = 12.dp)
                                .width(40.dp)
                                .height(4.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                        )

                        Spacer(modifier = Modifier.height(20.dp))
                    }

                    PlayerArtwork(
                        player = player,
                        artworkURL = chapterArtworkURL ?: currentItem.artworkURL,
                        // Embedded-art fallback from the book's own file when nothing is stored (most items).
                        embeddedArtwork = ItemArtwork(currentItem.uuid, currentItem.relativePath, currentItem.remoteURL),
                        isBuffering = playbackState == Player.STATE_BUFFERING && !isLocal,
                        showCloudBadge = !fullscreenActive && !isLocal && !currentItem.remoteURL.isNullOrEmpty(),
                        hasVideo = hasVideo,
                        isSheetVisible = !isHidden,
                        isFullscreen = fullscreenActive,
                        onToggleFullscreen = { isFullscreen = !isFullscreen },
                        onCastClick = { viewModel.toggleCastSheet() }
                    )

                    if (!fullscreenActive) {
                        Spacer(modifier = Modifier.height(12.dp))

                        // The chevrons always navigate chapters: back can restart/step to a previous chapter for
                        // any loaded book (or previous item); forward is available when there's a next chapter or item.
                        val canGoBack = viewModel.hasPreviousItem || chapters.isNotEmpty()
                        val canGoForward = viewModel.hasNextItem || chapters.size > 1

                        PlayerTitleNavRow(
                            title = if (viewModel.useChapterContext && currentChapter != null) currentChapter.title else currentItem.title,
                            canGoBack = canGoBack,
                            canGoForward = canGoForward,
                            // Double chevron when there's no previous/next chapter (tap crosses to prev/next book).
                            hasPreviousChapter = currentChapterIndex > 0,
                            hasNextChapter = currentChapterIndex in 0 until (chapters.size - 1),
                            onPrevious = { viewModel.playPrevious(context) },
                            onNext = { viewModel.playNext(context) }
                        )

                        // Keep the title row, seek bar, and time labels as one tight cluster (iOS groups
                        // them in a VStack spacing 4) — the flexible space belongs BELOW this unit (above
                        // the centered transport controls), not between the title and the slider.
                        Spacer(modifier = Modifier.height(4.dp))

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
                            },
                            onToggleContext = {
                                viewModel.updateUseChapterContext(context, !viewModel.useChapterContext)
                            },
                            onToggleRemainingTime = {
                                viewModel.updateUseRemainingTime(context, !viewModel.useRemainingTime)
                            }
                        )

                        // Center the transport controls in the space between the seek bar and the bottom
                        // bar — this weight plus the one below split the slack equally, so the controls stay
                        // centered on any screen instead of the old fixed 48dp pooling all slack below them
                        // (which made them float low on tall devices). Mirrors iOS PlayerView, which puts a
                        // Spacer() both above and below PlayControlsRowView.
                        Spacer(modifier = Modifier.weight(1f))

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

                        val sleepActive by viewModel.sleepTimerActive.collectAsStateWithLifecycle()
                        val sleepEndOfChapter by viewModel.sleepTimerIsEndOfChapter.collectAsStateWithLifecycle()
                        val sleepRemaining by viewModel.sleepTimerRemaining.collectAsStateWithLifecycle()

                        PlayerBottomBar(
                            speedLabel = "${if (playbackSpeed % 1.0f == 0.0f) playbackSpeed.toInt() else playbackSpeed}x",
                            sleepLabel = when {
                                sleepEndOfChapter -> stringResource(R.string.player_timer_active)
                                sleepActive -> sleepRemaining
                                else -> null
                            },
                            listOpensBookmarks = viewModel.listButtonOpens == "Bookmarks",
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
}

@Composable
private fun PlayerArtwork(
    player: Player?,
    artworkURL: String?,
    embeddedArtwork: ItemArtwork? = null,
    isBuffering: Boolean,
    showCloudBadge: Boolean,
    hasVideo: Boolean,
    isSheetVisible: Boolean = true,
    isFullscreen: Boolean = false,
    onToggleFullscreen: () -> Unit = {},
    onCastClick: () -> Unit = {}
) {
    val context = LocalContext.current
    var showControls by remember { mutableStateOf(true) }
    var blurredBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // Inflated only once a video track is selected — audio-only books (the common case on this
    // screen) never pay for the Media3 PlayerView. Dropping to the else branch on audio books
    // also lets Compose forget (and free) the view when a video book is swapped for one.
    val playerView = if (hasVideo) {
        remember(context) {
            val pv = LayoutInflater.from(context).inflate(R.layout.video_player_view, null) as PlayerView
            pv.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            pv
        }
    } else {
        null
    }

    LaunchedEffect(hasVideo, player, playerView, isSheetVisible) {
        if (hasVideo && player != null && playerView != null && isSheetVisible) {
            var hasReportedError = false
            // Double-buffered capture targets: getBitmap(Bitmap) fills a caller-owned bitmap, so
            // no allocation happens per capture. Two buffers alternate so the one Compose is
            // currently drawing is never written to mid-frame — and the reference change is what
            // triggers recomposition (a single reused bitmap wouldn't).
            val captureBuffers = arrayOf(
                Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888),
                Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            )
            var captureIndex = 0
            while (true) {
                // Only capture while the frame can actually change (or nothing is captured yet);
                // a paused player keeps its last frame, so the existing blur stays correct.
                if (player.isPlaying || blurredBitmap == null) {
                    val textureView = findTextureView(playerView)
                    if (textureView != null && textureView.isAvailable) {
                        try {
                            // getBitmap(target) is the documented TextureView readback (the
                            // caller-owned-bitmap overload — no per-call allocation), called on the
                            // main thread ON PURPOSE. The alternatives both trade this bounded cost
                            // (a 32×32 readback once per 1–2s) for undocumented-API risk:
                            //  - PixelCopy needs a second Surface over a SurfaceTexture ExoPlayer is
                            //    already producing into — non-standard, can disturb the producer.
                            //  - getBitmap off-main (withContext/capture thread) has no documented
                            //    thread-safety; it reads view/layer state the UI and render threads
                            //    mutate, racing the same "some GPUs" any stall concern is about.
                            val target = captureBuffers[captureIndex]
                            textureView.getBitmap(target)
                            blurredBitmap = target
                            captureIndex = 1 - captureIndex
                        } catch (e: Exception) {
                            if (!hasReportedError) {
                                android.util.Log.e("PlayerArtwork", "Failed to capture texture bitmap", e)
                                io.sentry.Sentry.captureException(e)
                                hasReportedError = true
                            }
                        }
                    }
                }
                // The blur is a slow-changing letterbox backdrop, not a live preview: 1s refreshes
                // are indistinguishable at 20dp blur, and while paused the loop just idles at a
                // slower heartbeat waiting for playback to resume.
                delay(if (player.isPlaying) 1_000 else 2_000)
            }
        } else if (!hasVideo) {
            blurredBitmap = null
        }
        // While the sheet is merely off-screen (dismiss animation / collapsed to the mini player)
        // the capture pauses but the last blur is kept, so reopening doesn't flash un-blurred.
    }

    val artworkBackground = if (isFullscreen) {
        Modifier.background(Color.Black)
    } else if (artworkURL == null && !hasVideo) {
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

    val artworkModifier = if (isFullscreen) {
        Modifier.fillMaxSize()
    } else {
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp)) // matches iOS ArtworkView .cornerRadius(12)
    }

    val clickableModifier = if (hasVideo) {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            // Announce the action to screen readers instead of an unlabeled clickable.
            onClickLabel = stringResource(R.string.player_toggle_video_controls)
        ) {
            showControls = !showControls
        }
    } else {
        Modifier
    }

    Box(
        modifier = artworkModifier
            .then(artworkBackground)
            .then(clickableModifier),
        contentAlignment = Alignment.Center
    ) {
        if (hasVideo && player != null && playerView != null) {
            if (blurredBitmap != null) {
                Image(
                    bitmap = blurredBitmap!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().blur(20.dp),
                    contentScale = ContentScale.Crop
                )
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))
                )
            }
            AndroidView(
                factory = { playerView },
                modifier = Modifier.fillMaxSize(),
                update = { view ->
                    view.player = player
                },
                onRelease = { view ->
                    view.player = null
                }
            )
        } else if (artworkURL != null) {
            AsyncImage(
                model = artworkURL,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else if (embeddedArtwork != null) {
            // No stored artworkURL: extract the embedded cover (local file, else remote stream) — the same
            // path the library list uses, so the player matches it instead of showing the empty gradient.
            // remember-ed (keyed by the model) so it isn't rebuilt on every recompose.
            val artworkRequest = remember(embeddedArtwork) {
                ImageRequest.Builder(context)
                    .data(embeddedArtwork)
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
        AnimatedVisibility(
            visible = !hasVideo || showControls,
            enter = fadeIn(animationSpec = tween(300)),
            exit = fadeOut(animationSpec = tween(300)),
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (hasVideo) {
                    IconButton(
                        onClick = onToggleFullscreen,
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.4f), CircleShape)
                    ) {
                        Icon(
                            imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                            contentDescription = if (isFullscreen) stringResource(R.string.player_exit_fullscreen) else stringResource(R.string.player_enter_fullscreen),
                            tint = Color.White
                        )
                    }
                }
                IconButton(
                    onClick = onCastClick,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.4f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Cast,
                        contentDescription = stringResource(R.string.player_cast),
                        tint = Color.White
                    )
                }
            }
        }
    }
}

// Media3 exposes embedded cover art in some .mp4/.m4b audiobooks as a real (single-frame,
// image-codec) video track. Treating those as video would swap the artwork for a PlayerView
// and start the blur-capture loop on a plain audiobook, so only genuine motion-video codecs
// count. Presence is checked rather than selection: the screen disables the video track type
// while stopped/collapsed, and that must not read back as "no video".
private val stillImageVideoMimeTypes = setOf(
    MimeTypes.VIDEO_MJPEG,
    "video/jpeg",
    "video/png",
    "video/bmp"
)

private fun Tracks.hasPlayableVideo(): Boolean = groups.any { group ->
    group.type == C.TRACK_TYPE_VIDEO && (0 until group.length).any { i ->
        val mime = group.getTrackFormat(i).sampleMimeType?.lowercase()
        mime != null && MimeTypes.isVideo(mime) && mime !in stillImageVideoMimeTypes
    }
}

private fun findTextureView(view: View): TextureView? {
    if (view is TextureView) return view
    if (view is ViewGroup) {
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            val result = findTextureView(child)
            if (result != null) return result
        }
    }
    return null
}

@Composable
private fun PlayerTitleNavRow(
    title: String,
    canGoBack: Boolean,
    canGoForward: Boolean,
    hasPreviousChapter: Boolean,
    hasNextChapter: Boolean,
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
                // Single chevron steps to the previous chapter; a double chevron signals there is no
                // previous chapter, so the tap crosses to the previous book (iOS parity).
                imageVector = if (hasPreviousChapter) Icons.Default.ChevronLeft else Icons.Default.KeyboardDoubleArrowLeft,
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
                // Double chevron signals there is no next chapter, so the tap crosses to the next book.
                imageVector = if (hasNextChapter) Icons.Default.ChevronRight else Icons.Default.KeyboardDoubleArrowRight,
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
    onValueChangeFinished: () -> Unit,
    onToggleContext: () -> Unit = {},
    onToggleRemainingTime: () -> Unit = {}
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
    // Position labels adopt hh:mm:ss as soon as the displayed total (chapter or whole book)
    // crosses an hour, so both sides of the bar always share one format.
    val hourFormat = if (useChapterContext && currentChapter != null) {
        (currentChapter.duration * 1000).toLong() >= HOUR_IN_MS
    } else {
        duration >= HOUR_IN_MS
    }
    val seekStateDescription = if (useChapterContext && currentChapter != null) {
        val chapterPos = (position - (currentChapter.start * 1000).toLong()).coerceAtLeast(0)
        stringResource(
            R.string.player_seek_position,
            formatTime(chapterPos, hourFormat),
            formatTime((currentChapter.duration * 1000).toLong(), hourFormat)
        )
    } else {
        stringResource(R.string.player_seek_position, formatTime(position, hourFormat), formatTime(duration, hourFormat))
    }
    BookPlayerSlider(
        // Constrain the seek bar's height so the thin track sits close to the title above and the time
        // labels below (the default Material slider reserves ~48dp of touch padding around the track, which
        // opened up the gaps). Scoped to the player; horizontal scrubbing spans the full width, unaffected.
        modifier = Modifier
            .height(28.dp)
            .semantics { stateDescription = seekStateDescription },
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
            formatTime(chapterPos.coerceAtLeast(0), hourFormat)
        } else {
            formatTime(displayPosition, hourFormat)
        }

        val rightLabel = if (useChapterContext && currentChapter != null) {
            val chapterDuration = (currentChapter.duration * 1000).toLong()
            val chapterPos = displayPosition - (currentChapter.start * 1000).toLong()
            if (useRemainingTime) {
                "-${formatTime((chapterDuration - chapterPos).coerceAtLeast(0), hourFormat)}"
            } else {
                formatTime(chapterDuration, hourFormat)
            }
        } else {
            if (useRemainingTime) {
                "-${formatTime((duration - displayPosition).coerceAtLeast(0), hourFormat)}"
            } else {
                formatTime(duration, hourFormat)
            }
        }

        Text(
            text = leftLabel,
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // Wide enough for the hh:mm:ss format (incl. the remaining-time minus sign).
            modifier = Modifier.width(84.dp)
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
            modifier = Modifier
                .weight(1f)
                // Tapping the center label swaps the whole bar between chapter and book context
                // (same setting as the player-settings toggle).
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = stringResource(R.string.player_toggle_progress_context)
                ) { onToggleContext() },
            maxLines = 1
        )
        Text(
            text = rightLabel,
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier
                // Wide enough for the hh:mm:ss format (incl. the remaining-time minus sign).
                .width(84.dp)
                // Tapping swaps remaining time <-> total length (same setting as the
                // player-settings toggle).
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = stringResource(R.string.player_toggle_remaining_time)
                ) { onToggleRemainingTime() }
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
    listOpensBookmarks: Boolean,
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
            icon = Icons.Default.BookmarkAdd,
            contentDescription = stringResource(R.string.player_add_bookmark),
            onClick = onBookmark
        )
        // Reflects the "list button opens" setting live: icon + label switch with the choice, so
        // changing it in the player settings sheet is visible immediately.
        PlayerBottomButton(
            icon = if (listOpensBookmarks) Icons.Default.Bookmarks else Icons.AutoMirrored.Filled.List,
            contentDescription = if (listOpensBookmarks) stringResource(R.string.player_bookmarks_title)
                                 else stringResource(R.string.player_chapters_title),
            onClick = onList
        )
        PlayerBottomButton(
            icon = Icons.Default.MoreHoriz,
            contentDescription = stringResource(R.string.common_more),
            onClick = onMore
        )
    }
}
