package com.tortugapower.audiobookplayer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.logic.ItemArtwork
import com.tortugapower.audiobookplayer.logic.PlaybackManager
import com.tortugapower.audiobookplayer.ui.theme.LocalBookPlayerColors

private val MiniPlayerPillHeight: Dp = 64.dp
private val MiniPlayerVerticalMargin: Dp = 8.dp

/** Total vertical space the floating mini player occupies (pill + top/bottom margins). */
val MiniPlayerBarHeight: Dp = MiniPlayerPillHeight + MiniPlayerVerticalMargin * 2

/**
 * Bottom inset (in [Dp]) that scrollable screens should reserve so their content clears the
 * floating mini player. Provided by MainScreen — [MiniPlayerBarHeight] when the pill is visible,
 * 0.dp otherwise. The analog of iOS's `@Environment(\.miniPlayerBottomInset)`.
 */
val LocalMiniPlayerInset = compositionLocalOf { 0.dp }

@Composable
fun MiniPlayer(modifier: Modifier = Modifier) {
    val currentItem = PlaybackManager.currentItem.collectAsStateWithLifecycle().value ?: return
    val isPlaying by PlaybackManager.isPlaying.collectAsStateWithLifecycle()
    val rewindInterval by PlaybackManager.rewindInterval.collectAsStateWithLifecycle()

    val title = currentItem.title
    val author = if (currentItem.author.isNullOrBlank()) {
        stringResource(R.string.library_unknown_author)
    } else {
        currentItem.author!!
    }
    // Combined VoiceOver-equivalent label, mirroring iOS's "Currently playing X by Y".
    val nowPlayingLabel = stringResource(R.string.miniplayer_now_playing, title, author)
    val showPlayerLabel = stringResource(R.string.miniplayer_show_player)

    // A drop shadow is invisible on a dark background (shadows are dark), so — like iOS, which
    // separates the pill with an adaptive material rather than a shadow — we use the shadow only
    // in light mode and a hairline border in dark mode.
    val isDark = LocalBookPlayerColors.current.useDarkVariant
    val pillShape = RoundedCornerShape(32.dp)
    val separation = if (isDark) {
        Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, pillShape)
    } else {
        Modifier.shadow(elevation = 6.dp, shape = pillShape)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = MiniPlayerVerticalMargin)
            .height(MiniPlayerPillHeight)
            .then(separation)
            .clip(pillShape)
            .background(MaterialTheme.colorScheme.surface)
            // Whole pill opens the full player; the now-playing label + onClickLabel describe it
            // to TalkBack as one element. The control buttons below are separate, focusable nodes
            // (we intentionally do NOT mergeDescendants, which would swallow them).
            .clickable(onClickLabel = showPlayerLabel) {
                PlaybackManager.setShowPlayer(true)
            }
            .semantics {
                contentDescription = nowPlayingLabel
                heading()
            }
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            val context = LocalContext.current
            val isLocal = remember(currentItem.relativePath, currentItem.type) {
                if (currentItem.type == com.tortugapower.audiobookplayer.database.entities.ItemType.FOLDER) true
                else if (currentItem.relativePath == null) false
                else {
                    val processedDir = java.io.File(context.filesDir, "Processed")
                    java.io.File(processedDir, currentItem.relativePath!!).exists()
                }
            }

            // Thumbnail (decorative — excluded from accessibility).
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
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .then(artworkBackground)
            ) {
                if (currentItem.artworkURL != null) {
                    AsyncImage(
                        model = currentItem.artworkURL,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    // Embedded-art fallback (same path as the library list), so the mini player matches it.
                    // remember-ed so it isn't rebuilt on every recompose (the mini player recomposes on each
                    // isPlaying / interval change).
                    val artworkRequest = remember(currentItem.uuid, currentItem.relativePath, currentItem.remoteURL) {
                        ImageRequest.Builder(context)
                            .data(ItemArtwork(currentItem.uuid, currentItem.relativePath, currentItem.remoteURL))
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
                if (!isLocal && !currentItem.remoteURL.isNullOrEmpty()) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val path = Path().apply {
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
                            .padding(2.dp)
                            .size(12.dp),
                        tint = Color(0xFF4285F4)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Visual title/author. The combined label lives on the pill (above), so this is
            // cleared from the accessibility tree to avoid a duplicate announcement.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clearAndSetSemantics {}
            ) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    text = author,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1
                )
            }

            IconButton(onClick = { PlaybackManager.seekBackward() }) {
                Icon(
                    imageVector = Icons.Default.Replay,
                    contentDescription = stringResource(R.string.player_rewind_seconds, rewindInterval),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }
            IconButton(onClick = { PlaybackManager.togglePlayPause() }) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = stringResource(if (isPlaying) R.string.player_pause else R.string.player_play),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

@Composable
private fun IconButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .semantics { role = Role.Button }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
