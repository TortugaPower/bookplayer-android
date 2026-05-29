package com.tortugapower.audiobookplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Path
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.outlined.Cloud
import com.tortugapower.audiobookplayer.R
import coil.compose.AsyncImage
import com.tortugapower.audiobookplayer.logic.PlaybackManager

@Composable
fun MiniPlayer() {
    val currentItem = PlaybackManager.currentItem ?: return
    val isPlaying = PlaybackManager.isPlaying

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(64.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable { PlaybackManager.showPlayerScreen = true }
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

            // Thumbnail
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
                }
                if (!isLocal && !currentItem.remoteURL.isNullOrEmpty()) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val path = Path().apply {
                            moveTo(size.width, size.height * 0.45f)
                            lineTo(size.width, size.height)
                            lineTo(size.width * 0.45f, size.height)
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

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = currentItem.title,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    text = if (currentItem.author.isNullOrBlank()) stringResource(R.string.library_unknown_author) else currentItem.author!!,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1
                )
            }

            IconButton(onClick = { PlaybackManager.seekBackward() }) {
                Icon(
                    imageVector = Icons.Default.Replay,
                    contentDescription = stringResource(R.string.player_rewind),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }
            IconButton(onClick = { PlaybackManager.togglePlayPause() }) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.player_play_pause),
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
