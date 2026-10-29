package com.tortugapower.audiobookplayer.ui.screens.library

import android.os.Build
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.logic.LibraryLayout
import com.tortugapower.audiobookplayer.logic.PlaybackManager
import com.tortugapower.audiobookplayer.viewmodel.LibraryViewModel
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The grid layouts (iOS `ItemListView+Grid` + `LibraryTileView`): a level's items as cards (the cover, then
 * the duration, title and author) or as covers alone, more to a row. A tap does what it does in the list,
 * and a long press starts selecting, as a row's does. Reordering stays with the list layout (as on iOS
 * before iOS 27).
 *
 * Laid out as rows, not a lazy grid: a lazy grid sizes each cell on its own, while a row here gives its
 * cards the tallest one's height, so a row lines up while each card fits its own text.
 */
@Composable
fun LibraryTileGrid(
    items: List<LibraryItemEntity>,
    layout: LibraryLayout,
    isSelectMode: Boolean,
    selectedItemUuids: Set<String>,
    libraryViewModel: LibraryViewModel,
    canDownload: Boolean,
    bottomInset: Dp,
    onClick: (LibraryItemEntity) -> Unit,
    onLongClick: (LibraryItemEntity) -> Unit,
    onOptions: (LibraryItemEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val style = if (layout == LibraryLayout.COVERS) TileStyle.COVER else TileStyle.CARD
    // Cards grow with the text size, as their text does: at the largest sizes, one or two to a row.
    val fontScale = LocalDensity.current.fontScale.coerceIn(1f, 2f)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val horizontalPadding = 16.dp
        val spacing = if (style == TileStyle.COVER) 8.dp else 16.dp
        val available = maxWidth - horizontalPadding * 2
        // About 150dp a card, 100dp a cover (140dp on a tablet), never wider than the screen.
        val minimumWidth = when (style) {
            TileStyle.CARD -> 150.dp * fontScale
            TileStyle.COVER -> if (maxWidth >= 600.dp) 140.dp else 100.dp
        }.coerceAtMost(available)
        val columns = max(1, ((available + spacing) / (minimumWidth + spacing)).toInt())
        val tileWidth = (available - spacing * (columns - 1)) / columns
        val rows = remember(items, columns) { items.chunked(columns) }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // Clears the floating mini player while the grid still scrolls behind it.
            contentPadding = PaddingValues(
                start = horizontalPadding,
                end = horizontalPadding,
                top = 8.dp,
                bottom = bottomInset + 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(spacing)
        ) {
            items(rows, key = { row -> row.joinToString(",") { it.uuid } }) { row ->
                Row(
                    modifier = Modifier.height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(spacing)
                ) {
                    row.forEach { item ->
                        LibraryTile(
                            item = item,
                            style = style,
                            tileWidth = tileWidth,
                            isSelectMode = isSelectMode,
                            isSelected = item.uuid in selectedItemUuids,
                            libraryViewModel = libraryViewModel,
                            canDownload = canDownload,
                            onClick = { onClick(item) },
                            onLongClick = { onLongClick(item) },
                            onOptions = { onOptions(item) },
                            modifier = Modifier
                                .width(tileWidth)
                                .fillMaxHeight()
                        )
                    }
                }
            }
        }
    }
}

enum class TileStyle { CARD, COVER }

/**
 * One item in the grid. A folder's cover is a stack: two dimmed leaves peek above it, each a step
 * narrower, and the front cover is shorter by the stack's height, so every tile in a row is as tall.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryTile(
    item: LibraryItemEntity,
    style: TileStyle,
    tileWidth: Dp,
    isSelectMode: Boolean,
    isSelected: Boolean,
    libraryViewModel: LibraryViewModel,
    canDownload: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onOptions: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val download = rememberItemDownload(item, libraryViewModel)
    val currentPlayingItem by PlaybackManager.currentItem.collectAsState()
    val isHighlighted = currentPlayingItem?.uuid == item.uuid
    val showOriginalFileName by libraryViewModel.showOriginalFileName.collectAsState()
    val showPercentage by libraryViewModel.showProgressAsPercentage.collectAsState()

    val isFolder = item.type == ItemType.FOLDER
    val stackStep = if (isFolder) (tileWidth.value * 0.045f).roundToInt().dp else 0.dp
    val coverHeight = tileWidth - stackStep * 2
    val shape = RoundedCornerShape(if (style == TileStyle.CARD) 16.dp else 8.dp)

    val description = itemAccessibilityDescription(item, download, canDownload)
    val optionsLabel = stringResource(R.string.library_options_title)

    Box(modifier = modifier.padding(top = stackStep * 2)) {
        if (isFolder) {
            StackedLeaf(level = 2, dimming = 0.45f, stackStep = stackStep, style = style)
            StackedLeaf(level = 1, dimming = 0.25f, stackStep = stackStep, style = style)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (style == TileStyle.CARD) Modifier.fillMaxHeight() else Modifier)
                .clip(shape)
                .background(
                    if (style == TileStyle.CARD) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent
                )
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = if (isSelectMode) null else {
                        {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongClick()
                        }
                    }
                )
                // One element for TalkBack, the one that's tapped: its whole description, and its state.
                .semantics {
                    contentDescription = description
                    role = Role.Button
                    if (isSelectMode) {
                        selected = isSelected
                    } else {
                        // The list's swipe, which a grid doesn't have: the item's options.
                        customActions = listOf(CustomAccessibilityAction(optionsLabel) { onOptions(); true })
                    }
                }
        ) {
            TileCover(
                item = item,
                style = style,
                tileWidth = tileWidth,
                coverHeight = coverHeight,
                isFolder = isFolder,
                isHighlighted = isHighlighted,
                showPercentage = showPercentage,
                isSelectMode = isSelectMode,
                isSelected = isSelected,
                download = download,
                canDownload = canDownload,
                onDownload = { libraryViewModel.startDownload(item) }
            )
            if (style == TileStyle.CARD) {
                TileDetails(item, itemDisplayTitle(item, showOriginalFileName), isHighlighted)
            }
        }
    }
}

/** One of the two leaves behind a folder's cover: the placeholder artwork, dimmed, the furthest the darkest. */
@Composable
private fun StackedLeaf(level: Int, dimming: Float, stackStep: Dp, style: TileStyle) {
    Box(
        modifier = Modifier
            .padding(horizontal = stackStep * level)
            .offset(y = -stackStep * level)
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(if (style == TileStyle.CARD) 12.dp else 6.dp))
            .background(placeholderBrush())
            .background(Color.Black.copy(alpha = dimming))
    )
}

@Composable
private fun placeholderBrush(): Brush =
    Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary))

@Composable
private fun TileCover(
    item: LibraryItemEntity,
    style: TileStyle,
    tileWidth: Dp,
    coverHeight: Dp,
    isFolder: Boolean,
    isHighlighted: Boolean,
    showPercentage: Boolean,
    isSelectMode: Boolean,
    isSelected: Boolean,
    download: ItemDownloadUi,
    canDownload: Boolean,
    onDownload: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(coverHeight)
            .then(if (style == TileStyle.COVER) Modifier.clip(RoundedCornerShape(8.dp)) else Modifier)
            .then(if (item.artworkURL == null) Modifier.background(placeholderBrush()) else Modifier)
    ) {
        if (isFolder) {
            // Not cropped to the shorter shape: fitted, on a blurred copy of itself (blur needs Android 12;
            // before it the copy is only dimmed).
            LibraryItemArtworkImage(
                item,
                ContentScale.Crop,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(24.dp) else Modifier
            )
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.2f)))
            LibraryItemArtworkImage(item, ContentScale.Fit)
        } else {
            LibraryItemArtworkImage(item, ContentScale.Crop)
        }

        if (!isSelectMode) {
            ProgressMark(
                item = item,
                style = style,
                tileWidth = tileWidth,
                isHighlighted = isHighlighted,
                showPercentage = showPercentage,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
            )
        }

        DownloadMark(
            download = download,
            canDownload = canDownload,
            onDownload = onDownload,
            modifier = Modifier.align(Alignment.BottomEnd)
        )

        if (isSelectMode) {
            SelectionMark(isSelected)
        }
    }
}

/**
 * A list row's progress mark, on the cover's corner: the wheel (or the percentage with that option), the
 * check once finished, nothing before it's started. On a light disc with a shadow, so it reads on any cover.
 * A card's grows with the card, to about 15% of its width.
 */
@Composable
private fun ProgressMark(
    item: LibraryItemEntity,
    style: TileStyle,
    tileWidth: Dp,
    isHighlighted: Boolean,
    showPercentage: Boolean,
    modifier: Modifier = Modifier
) {
    val progress = item.percentCompleted.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
    val isFinished = item.isFinished || progress >= 1.0
    if (!isFinished && progress <= 0.0) return

    val size = if (style == TileStyle.CARD) maxOf(24.dp, tileWidth * 0.15f) else 24.dp
    val tint = if (isHighlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val background = MaterialTheme.colorScheme.surface

    when {
        isFinished -> Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier
                .size(size)
                .shadow(3.dp, CircleShape)
                .background(background, CircleShape)
        )
        showPercentage -> Text(
            text = "${(progress * 100).roundToInt()}%",
            style = if (style == TileStyle.CARD) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = tint,
            modifier = modifier
                .shadow(3.dp, CircleShape)
                .background(background, CircleShape)
                .padding(horizontal = 8.dp, vertical = 2.dp)
        )
        else -> PieProgressIcon(
            progress = progress.toFloat(),
            color = tint,
            modifier = modifier
                .size(size)
                .shadow(3.dp, CircleShape)
                .background(background, CircleShape)
        )
    }
}

/**
 * The download corner, as the list's artwork has it: a ring while downloading, else a cloud for audio that
 * isn't on the device (tap to download), or an error mark when this account can't download it.
 */
@Composable
private fun DownloadMark(
    download: ItemDownloadUi,
    canDownload: Boolean,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    val progress = download.progress
    if (progress != null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(40.dp),
                color = Color.White,
                strokeWidth = 3.dp,
                trackColor = Color.White.copy(alpha = 0.3f)
            )
        }
    } else if (download.showCloud) {
        val downloadLabel = stringResource(R.string.common_download)
        Box(
            modifier = modifier
                .padding(6.dp)
                .size(32.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.6f))
                .then(
                    if (canDownload && !download.isDownloading) {
                        Modifier.clickable(onClickLabel = downloadLabel, onClick = onDownload)
                            .semantics { contentDescription = downloadLabel }
                    } else {
                        Modifier.clearAndSetSemantics { }
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                // Signed out or FREE: the file is missing and can't be fetched again, an error mark rather
                // than a download button that does nothing.
                imageVector = if (canDownload) Icons.Outlined.Cloud else Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = if (canDownload) Color(0xFF4285F4) else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** As iOS (after Files): a ring in the middle of the cover, filled with a check once selected, over a gray wash. */
@Composable
private fun SelectionMark(isSelected: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(if (isSelected) Modifier.background(Color.Gray.copy(alpha = 0.5f)) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .shadow(2.dp, CircleShape)
                .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.15f), CircleShape)
                .border(2.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/** A card's text: the duration, the title (the playing item's in the accent color) and the author or file count. */
@Composable
private fun TileDetails(item: LibraryItemEntity, title: String, isHighlighted: Boolean) {
    // A third line at the largest text sizes, where wrapping beats cutting a title short.
    val titleLines = if (LocalDensity.current.fontScale >= 1.3f) 3 else 2
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        val duration = itemDurationText(item)
        if (duration.isNotEmpty()) {
            Text(
                text = duration,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        Text(
            text = title.ifBlank { stringResource(R.string.library_unknown_title) },
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = if (isHighlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = titleLines,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = itemDetailsText(item),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
