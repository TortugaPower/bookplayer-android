package com.tortugapower.audiobookplayer.wear.presentation

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.ExperimentalWearFoundationApi
import androidx.wear.compose.foundation.HierarchicalFocusCoordinator
import androidx.wear.compose.foundation.rememberActiveFocusRequester
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.CompactButton
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.PositionIndicatorAlignment
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.tortugapower.audiobookplayer.wear.R
import kotlinx.coroutines.delay

/**
 * Rotary pixels per volume step. The crown reports accumulated scroll pixels; one volume step per this many
 * keeps a full crown turn from slamming the ~15 media-stream steps at once. Tuned on-device.
 */
private const val CROWN_VOLUME_STEP_PX = 60f

/** How long the peripheral volume indicator lingers after the last crown step before fading out. */
private const val VOLUME_INDICATOR_TIMEOUT_MS = 2000L

/**
 * Full now-playing controls for the current item. A fixed, non-scrolling layout (title/author, the main
 * transport row, then the overflow + chapters buttons) so everything — including the bottom buttons — is
 * reachable without scrolling.
 *
 * When [onCrownVolume] is set, the rotary crown adjusts volume (up = true / down = false) instead of
 * scrolling — the layout fits without scrolling, so the crown is free. Standalone binds it to the watch's
 * own volume and passes [volume] (0..1) so a peripheral arched indicator shows the level; remote sends a
 * command to the phone (which shows its own volume HUD, so [volume] is null there). Pixels accumulate so
 * one detent ≈ one step.
 */
@OptIn(ExperimentalWearFoundationApi::class)
@Composable
fun NowPlayingScreen(
    state: RemoteUiState,
    onPlayPause: () -> Unit,
    onSkipBackward: () -> Unit,
    onSkipForward: () -> Unit,
    onMore: () -> Unit,
    onChapters: () -> Unit,
    onCrownVolume: ((up: Boolean) -> Unit)? = null,
    volume: Float? = null,
    progress: Float? = null,
) {
    // Plain holder (not State) so accumulating rotary pixels doesn't trigger recomposition.
    val crownAccumulator = remember { FloatArray(1) }
    // Bumped on each volume step to show, then auto-hide, the peripheral volume indicator.
    var crownTick by remember { mutableIntStateOf(0) }
    var volumeIndicatorVisible by remember { mutableStateOf(false) }
    LaunchedEffect(crownTick) {
        if (crownTick == 0) return@LaunchedEffect
        volumeIndicatorVisible = true
        delay(VOLUME_INDICATOR_TIMEOUT_MS)
        volumeIndicatorVisible = false
    }

    Scaffold(
        timeText = { TimeText() },
        positionIndicator = {
            // Peripheral arched volume indicator (left edge, like the system now-playing) — standalone only.
            if (volume != null && volumeIndicatorVisible) {
                PositionIndicator(value = { volume }, position = PositionIndicatorAlignment.Left)
            }
        },
    ) {
        val nowPlaying = state.nowPlaying
        if (nowPlaying == null) {
            CenterMessage {
                Text(
                    text = stringResource(R.string.wear_nothing_playing),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.body2,
                )
            }
            return@Scaffold
        }

        // Crown → volume: the transport buttons and the scroll container are all focusable, so a plain
        // requestFocus() gets stolen back within a frame (the crown never fires). HierarchicalFocusCoordinator
        // owns which subtree holds focus and re-asserts it (the same mechanism ScalingLazyColumn uses for
        // crown scrolling); rememberActiveFocusRequester() hands us the requester it drives. The focus target
        // is a PARENT of the scrolling column, not the column itself — a verticalScroll installs its own focus
        // target that would otherwise reclaim focus.
        HierarchicalFocusCoordinator(requiresFocus = { onCrownVolume != null }) {
            val focusRequester = rememberActiveFocusRequester()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (onCrownVolume != null) {
                            Modifier
                                .onRotaryScrollEvent { event ->
                                    crownAccumulator[0] += event.verticalScrollPixels
                                    while (crownAccumulator[0] >= CROWN_VOLUME_STEP_PX) {
                                        onCrownVolume(true)
                                        crownAccumulator[0] -= CROWN_VOLUME_STEP_PX
                                        crownTick++
                                    }
                                    while (crownAccumulator[0] <= -CROWN_VOLUME_STEP_PX) {
                                        onCrownVolume(false)
                                        crownAccumulator[0] += CROWN_VOLUME_STEP_PX
                                        crownTick++
                                    }
                                    true // the crown is ours on this screen — don't fall through to scroll
                                }
                                .focusRequester(focusRequester)
                                .focusable()
                        } else {
                            Modifier
                        },
                    ),
            ) {
                // Fixed layout that fits without scrolling at normal font (so the bottom buttons are visible),
                // but scrolls as a fallback if content overflows — e.g. at large accessibility font scale.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    // Single line that marquee-scrolls when it overflows (like the system now-playing).
                    Text(
                        text = nowPlaying.title,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        softWrap = false,
                        style = MaterialTheme.typography.title3,
                        modifier = Modifier
                            .fillMaxWidth()
                            .basicMarquee(),
                    )
                    if (nowPlaying.author.isNotBlank()) {
                        Text(
                            text = nowPlaying.author,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            style = MaterialTheme.typography.caption1,
                        )
                    }

                    Spacer(Modifier.height(6.dp))

                    // Main transport: rewind / play-pause / forward. Rewind and forward are vertical pills;
                    // play/pause carries the whole-book progress ring (matching the system now-playing).
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TransportPillButton(
                            iconRes = R.drawable.ic_wear_rewind,
                            contentDescription = stringResource(R.string.wear_rewind_seconds, state.rewindInterval),
                            onClick = onSkipBackward,
                        )
                        PlayPauseButton(
                            isPlaying = state.isPlaying,
                            progress = progress,
                            onClick = onPlayPause,
                        )
                        TransportPillButton(
                            iconRes = R.drawable.ic_wear_forward,
                            contentDescription = stringResource(R.string.wear_forward_seconds, state.forwardInterval),
                            onClick = onSkipForward,
                        )
                    }

                    Spacer(Modifier.height(6.dp))

                    // Secondary: chapters on the left, overflow (speed/boost/sleep) on the right.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (nowPlaying.chapters.isNotEmpty()) {
                            CompactButton(onClick = onChapters) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_wear_chapters),
                                    contentDescription = stringResource(R.string.wear_chapters),
                                )
                            }
                        }
                        CompactButton(onClick = onMore) {
                            Icon(
                                painter = painterResource(R.drawable.ic_wear_more),
                                contentDescription = stringResource(R.string.wear_more_controls),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Rewind / forward as a vertical pill (mirrors the system now-playing better than a plain circle). */
@Composable
private fun TransportPillButton(iconRes: Int, contentDescription: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(width = 40.dp, height = 60.dp),
        shape = RoundedCornerShape(percent = 50),
    ) {
        Icon(painter = painterResource(iconRes), contentDescription = contentDescription)
    }
}

/**
 * Play/pause with an optional whole-book [progress] ring wrapping the circular button (like the system
 * now-playing). No ring when [progress] is null (remote mode, where the phone owns the position).
 */
@Composable
private fun PlayPauseButton(isPlaying: Boolean, progress: Float?, onClick: () -> Unit) {
    Box(contentAlignment = Alignment.Center) {
        if (progress != null) {
            CircularProgressIndicator(
                progress = progress,
                modifier = Modifier.size(60.dp),
                strokeWidth = 3.dp,
                indicatorColor = MaterialTheme.colors.primary,
                trackColor = MaterialTheme.colors.onSurface.copy(alpha = 0.2f),
            )
        }
        Button(onClick = onClick) {
            Icon(
                painter = painterResource(if (isPlaying) R.drawable.ic_wear_pause else R.drawable.ic_wear_play),
                contentDescription = stringResource(if (isPlaying) R.string.wear_pause else R.string.wear_play),
            )
        }
    }
}
