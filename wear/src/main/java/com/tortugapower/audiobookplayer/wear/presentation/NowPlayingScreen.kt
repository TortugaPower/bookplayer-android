package com.tortugapower.audiobookplayer.wear.presentation

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.CompactButton
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.tortugapower.audiobookplayer.wear.R

/**
 * Full now-playing controls for the current item. A fixed, non-scrolling layout (title/author, the main
 * transport row, then the overflow + chapters buttons) so everything — including the bottom buttons — is
 * reachable without scrolling.
 */
@Composable
fun NowPlayingScreen(
    state: RemoteUiState,
    onPlayPause: () -> Unit,
    onSkipBackward: () -> Unit,
    onSkipForward: () -> Unit,
    onMore: () -> Unit,
    onChapters: () -> Unit,
) {
    Scaffold(timeText = { TimeText() }) {
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

        Column(
            modifier = Modifier
                .fillMaxSize()
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

            // Main transport: rewind / play-pause / forward.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onSkipBackward) {
                    Icon(
                        painter = painterResource(R.drawable.ic_wear_rewind),
                        contentDescription = stringResource(R.string.wear_rewind_seconds, state.rewindInterval),
                    )
                }
                Button(onClick = onPlayPause) {
                    Icon(
                        painter = painterResource(
                            if (state.isPlaying) R.drawable.ic_wear_pause else R.drawable.ic_wear_play,
                        ),
                        contentDescription = stringResource(
                            if (state.isPlaying) R.string.wear_pause else R.string.wear_play,
                        ),
                    )
                }
                Button(onClick = onSkipForward) {
                    Icon(
                        painter = painterResource(R.drawable.ic_wear_forward),
                        contentDescription = stringResource(R.string.wear_forward_seconds, state.forwardInterval),
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            // Secondary: chapters on the left, overflow (speed/boost/sleep) on the right, as compact buttons.
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
