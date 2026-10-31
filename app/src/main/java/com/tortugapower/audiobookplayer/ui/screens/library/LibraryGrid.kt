package com.tortugapower.audiobookplayer.ui.screens.library

import android.os.Build
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.shadow.Shadow
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
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.logic.LibraryLayout
import com.tortugapower.audiobookplayer.logic.PlaybackManager
import com.tortugapower.audiobookplayer.viewmodel.LibraryViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The grid layouts (iOS `ItemListView+Grid` + `LibraryTileView`): a level's items as cards (the cover, then
 * the duration, title and author) or as covers alone, more to a row. A tap does what it does in the list,
 * and a long press starts selecting, as a row's does; moving on from it drags the item to a new place, where
 * the level's order can be saved ([canReorder]). The caller holds the order: [onMove] moves an item in it, and
 * [onDragStopped] saves it.
 *
 * A lazy grid sizes each cell on its own, so a card's text area is given its row's tallest, measured ahead from
 * the row's texts ([cardTextHeight]): a row lines up while each card fits its own text.
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
    canReorder: Boolean,
    onMove: (from: Int, to: Int) -> Unit,
    onDragStarted: (LibraryItemEntity) -> Unit,
    onDragStopped: () -> Unit,
    modifier: Modifier = Modifier
) {
    val style = if (layout == LibraryLayout.COVERS) TileStyle.COVER else TileStyle.CARD
    // Tiles grow with the text size (iOS `@ScaledMetric`): at the largest sizes, fewer and bigger to a row.
    val fontScale = LocalDensity.current.fontScale.coerceIn(1f, 2f)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val horizontalPadding = 16.dp
        val spacing = if (style == TileStyle.COVER) 8.dp else 16.dp
        val available = maxWidth - horizontalPadding * 2
        // About 150dp a card, 100dp a cover (140dp on a tablet), never wider than the screen.
        val minimumWidth = when (style) {
            TileStyle.CARD -> 150.dp * fontScale
            TileStyle.COVER -> (if (maxWidth >= 600.dp) 140.dp else 100.dp) * fontScale
        }.coerceAtMost(available)
        val columns = max(1, ((available + spacing) / (minimumWidth + spacing)).toInt())
        val tileWidth = (available - spacing * (columns - 1)) / columns
        val showOriginalFileName by libraryViewModel.showOriginalFileName.collectAsState()
        // Each card measures its row's texts; the cache lets a row's cards share the work.
        val textMeasurer = rememberTextMeasurer(cacheSize = 64)
        val gridState = rememberLazyGridState()
        // Scrolls on its own near the edges while dragging, stopping short of the mini player.
        val reorderState = rememberReorderableLazyGridState(
            lazyGridState = gridState,
            scrollThresholdPadding = PaddingValues(bottom = bottomInset)
        ) { from, to -> onMove(from.index, to.index) }

        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            // Clears the floating mini player while the grid still scrolls behind it.
            contentPadding = PaddingValues(
                start = horizontalPadding,
                end = horizontalPadding,
                top = 8.dp,
                bottom = bottomInset + 16.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalArrangement = Arrangement.spacedBy(spacing)
        ) {
            itemsIndexed(items, key = { _, item -> item.uuid }) { index, item ->
                val textHeight = if (style == TileStyle.CARD) {
                    val rowStart = index - index % columns
                    val rowLines = items.subList(rowStart, minOf(rowStart + columns, items.size)).map { sibling ->
                        cardLines(sibling, itemDisplayTitle(sibling, showOriginalFileName), isHighlighted = false)
                    }
                    rememberCardTextHeight(rowLines, tileWidth, textMeasurer)
                } else {
                    0.dp
                }
                ReorderableItem(reorderState, key = item.uuid, enabled = canReorder) { isDragging ->
                    LibraryTile(
                        item = item,
                        style = style,
                        tileWidth = tileWidth,
                        textHeight = textHeight,
                        isSelectMode = isSelectMode,
                        isSelected = item.uuid in selectedItemUuids,
                        isDragging = isDragging,
                        libraryViewModel = libraryViewModel,
                        canDownload = canDownload,
                        onClick = { onClick(item) },
                        onLongClick = { onLongClick(item) },
                        onOptions = { onOptions(item) },
                        dragHandle = if (canReorder) {
                            Modifier.longPressDraggableHandle(
                                onDragStarted = { onDragStarted(item) },
                                onDragStopped = onDragStopped
                            )
                        } else {
                            null
                        }
                    )
                }
            }
        }
    }
}

private enum class TileStyle { CARD, COVER }

/** The progress mark's inset from the cover's corner. */
private val MARK_INSET = 8.dp

/** Lifts the progress mark off its cover: iOS's shadow, black at 50%, a 3 blur, 2 down. */
private val MARK_SHADOW = Shadow(radius = 3.dp, color = Color.Black.copy(alpha = 0.5f), offset = DpOffset(0.dp, 2.dp))

/** Darkens a folder's backdrop copy where it has pixels, before Android 12 (no blur). */
private val UNBLURRED_COPY_DIM = ColorFilter.tint(Color.Black.copy(alpha = 0.2f), BlendMode.SrcAtop)

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
    textHeight: Dp,
    isSelectMode: Boolean,
    isSelected: Boolean,
    isDragging: Boolean,
    libraryViewModel: LibraryViewModel,
    canDownload: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onOptions: () -> Unit,
    // The long press that selects and, moving on, drags; null where the order can't be saved.
    dragHandle: Modifier?,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val download = rememberItemDownload(item, libraryViewModel)
    val currentPlayingItem by PlaybackManager.currentItem.collectAsState()
    // The playing book, or the folder holding it, as in the list.
    val isHighlighted = isPlayingOrHoldsPlaying(item, currentPlayingItem)
    val showOriginalFileName by libraryViewModel.showOriginalFileName.collectAsState()
    val showPercentage by libraryViewModel.showProgressAsPercentage.collectAsState()

    val isFolder = item.type == ItemType.FOLDER
    val stackStep = if (isFolder) (tileWidth.value * 0.045f).roundToInt().dp else 0.dp
    val coverHeight = tileWidth - stackStep * 2
    val shape = RoundedCornerShape(if (style == TileStyle.CARD) 16.dp else 8.dp)
    // Lifted off the grid while dragged.
    val dragElevation by animateDpAsState(if (isDragging) 8.dp else 0.dp, label = "dragElevation")

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
                .shadow(dragElevation, shape)
                .clip(shape)
                .background(
                    if (style == TileStyle.CARD) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent
                )
                .combinedClickable(
                    onClick = onClick,
                    // With a drag handle, its long press selects.
                    onLongClick = if (isSelectMode || dragHandle != null) null else {
                        {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongClick()
                        }
                    }
                )
                // Inside the click, so it sees each touch first: a long press let go without moving is used up
                // here and doesn't also count as a tap.
                .then(dragHandle ?: Modifier)
                // One element for TalkBack, the one that's tapped: its whole description, and its state.
                .semantics {
                    contentDescription = description
                    role = Role.Button
                    if (isSelectMode) {
                        selected = isSelected
                    } else {
                        // The list's swipe, which a grid doesn't have: the item's options, its one action.
                        customActions = listOf(CustomAccessibilityAction(optionsLabel) { onOptions(); true })
                        // The drag handle's long press, for TalkBack, Switch Access and Voice Access: it selects.
                        if (dragHandle != null) {
                            onLongClick {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onLongClick()
                                true
                            }
                        }
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
                title = itemDisplayTitle(item, showOriginalFileName),
                download = download,
                canDownload = canDownload
            )
            if (style == TileStyle.CARD) {
                TileDetails(item, itemDisplayTitle(item, showOriginalFileName), isHighlighted, textHeight)
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
    title: String,
    download: ItemDownloadUi,
    canDownload: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(coverHeight)
            .then(if (style == TileStyle.COVER) Modifier.clip(RoundedCornerShape(8.dp)) else Modifier)
            .then(if (item.artworkURL == null) Modifier.background(placeholderBrush()) else Modifier)
    ) {
        if (style == TileStyle.COVER && item.artworkURL == null) {
            PlaceholderText(item, title)
        }
        if (isFolder) {
            // Not cropped to the shorter shape: fitted, on a blurred copy of itself (iOS). Blur needs Android 12;
            // before it the copy is dimmed instead. Only the copy: a folder without artwork keeps a book's
            // placeholder. One painter for both, so the artwork loads once.
            rememberItemArtworkPainter(item)?.let { artwork ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    ItemArtworkImage(artwork, ContentScale.Crop, Modifier.blur(24.dp))
                } else {
                    ItemArtworkImage(artwork, ContentScale.Crop, colorFilter = UNBLURRED_COPY_DIM)
                }
                ItemArtworkImage(artwork, ContentScale.Fit)
            }
        } else {
            LibraryItemArtworkImage(item, ContentScale.Crop)
        }

        DownloadMark(
            item = item,
            style = style,
            tileWidth = tileWidth,
            download = download,
            canDownload = canDownload,
            modifier = Modifier.align(Alignment.BottomEnd)
        )

        // Over the artwork and the corner, under the progress mark (iOS draws the mark above it).
        if (isHighlighted) {
            PlayingWash()
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
                    .padding(MARK_INSET)
                    // The tile's description already says it.
                    .clearAndSetSemantics { }
            )
        }

        if (isSelectMode) {
            SelectionMark(isSelected)
        }
    }
}

/**
 * A list row's progress mark, on the cover's corner: the wheel (or the percentage with that option), the
 * check once finished, nothing before it's started. On the list's background with a shadow, so it reads on
 * any cover. A card's wheel grows with the card, to 15% of its width (iOS `LibraryTileView.progressMark`).
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

    val size = if (style == TileStyle.CARD) maxOf(PROGRESS_WHEEL_SIZE, tileWidth * 0.15f) else PROGRESS_WHEEL_SIZE
    val tint = if (isHighlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val background = MaterialTheme.colorScheme.surface

    when {
        isFinished -> ProgressWheel(
            progress = 1.0,
            isHighlighted = isHighlighted,
            modifier = modifier
                .size(size)
                .dropShadow(CircleShape, MARK_SHADOW)
        )
        showPercentage -> Text(
            text = "${(progress * 100).roundToInt()}%",
            style = if (style == TileStyle.CARD) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = tint,
            modifier = modifier
                .dropShadow(CircleShape, MARK_SHADOW)
                .background(background, CircleShape)
                .padding(horizontal = 8.dp, vertical = 2.dp)
        )
        else -> ProgressWheel(
            progress = progress,
            isHighlighted = isHighlighted,
            modifier = modifier
                .size(size)
                .dropShadow(CircleShape, MARK_SHADOW)
                // Under the gap inside the wheel's outline (the finished disc is opaque already).
                .background(background, CircleShape)
        )
    }
}

/**
 * The list's download corner grown with the cover (iOS `ItemArtworkView`): its triangle runs 30% along each
 * edge of a cover alone and 25% of a card's. It only shows the state, as on iOS: a tap anywhere on a tile does
 * what the tile does, and downloading is in the item's options. A ring over the cover while downloading.
 */
@Composable
private fun DownloadMark(
    item: LibraryItemEntity,
    style: TileStyle,
    tileWidth: Dp,
    download: ItemDownloadUi,
    canDownload: Boolean,
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
        val legs = if (style == TileStyle.COVER) 0.3f else 0.25f
        DownloadCorner(
            item = item,
            canDownload = canDownload,
            box = tileWidth * (legs / LEG_FRACTION),
            // The tile's description already says when its audio can't be played.
            modifier = modifier.clearAndSetSemantics { }
        )
    }
}

/**
 * A cover alone with no artwork: nothing else on screen names it, so the author, then the title, in white at
 * the bottom (iOS's `cover` placeholder).
 */
@Composable
private fun PlaceholderText(item: LibraryItemEntity, title: String) {
    // Kept below the progress mark: a folder's front cover is shorter, and at larger text sizes three title
    // lines and the author would otherwise reach it. The title gets the lines that fit, up to three, worked
    // out from the space here, not from a measured size.
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 8.dp, end = 8.dp, bottom = 8.dp, top = MARK_INSET + PROGRESS_WHEEL_SIZE + 4.dp)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.BottomStart
    ) {
        val style = MaterialTheme.typography.labelSmall
        val lineHeight = with(LocalDensity.current) { style.lineHeight.toDp() }
        val titleLines = ((maxHeight - lineHeight - 2.dp) / lineHeight).toInt().coerceIn(1, 3)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = itemDetailsText(item),
                style = style,
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = title.ifBlank { stringResource(R.string.library_unknown_title) },
                style = style,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = titleLines,
                overflow = TextOverflow.Ellipsis
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

/** One line of a card's text: what [TileDetails] draws and what [rememberCardTextHeight] measures. */
private data class CardLine(val text: String, val style: TextStyle, val maxLines: Int, val color: Color)

/** A card's text: the duration, the title (the playing item's in the accent color) and the author or file count. */
@Composable
private fun cardLines(item: LibraryItemEntity, title: String, isHighlighted: Boolean): List<CardLine> {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    // A third title line at the largest text sizes, where wrapping beats cutting a title short.
    val titleLines = if (LocalDensity.current.fontScale >= 1.3f) 3 else 2
    return listOfNotNull(
        itemDurationText(item).takeIf { it.isNotEmpty() }?.let {
            CardLine(it, MaterialTheme.typography.bodySmall, 1, secondary)
        },
        CardLine(
            text = title.ifBlank { stringResource(R.string.library_unknown_title) },
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
            maxLines = titleLines,
            color = if (isHighlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        ),
        CardLine(itemDetailsText(item), MaterialTheme.typography.bodySmall, 2, secondary)
    )
}

private val CARD_TEXT_PADDING = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp)
private val CARD_LINE_SPACING = 2.dp

/**
 * The height [TileDetails] takes for the tallest of a row's cards ([rowLines], each card's lines) in a card [tileWidth]
 * wide, measured from the lines alone, before any layout: nothing measured on screen feeds back into it. Remembered
 * for the row's text, so the library's updates (a playing book's progress) recompose its cards without measuring
 * them again.
 */
@Composable
private fun rememberCardTextHeight(rowLines: List<List<CardLine>>, tileWidth: Dp, measurer: TextMeasurer): Dp {
    val density = LocalDensity.current
    return remember(rowLines, tileWidth, density) {
        with(density) {
            val constraints = Constraints(maxWidth = (tileWidth - 24.dp).roundToPx().coerceAtLeast(0))
            val padding = CARD_TEXT_PADDING.calculateTopPadding().roundToPx() +
                CARD_TEXT_PADDING.calculateBottomPadding().roundToPx()
            rowLines.maxOf { lines ->
                val textHeight = lines.sumOf { line ->
                    measurer.measure(
                        text = line.text,
                        style = line.style,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = line.maxLines,
                        constraints = constraints
                    ).size.height
                }
                textHeight + CARD_LINE_SPACING.roundToPx() * (lines.size - 1) + padding
            }.toDp()
        }
    }
}

@Composable
private fun TileDetails(item: LibraryItemEntity, title: String, isHighlighted: Boolean, rowHeight: Dp) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // As tall as the row's tallest card text, so the row's cards line up.
            .heightIn(min = rowHeight)
            // Read once, in the tile's description, not again merged after it (as the list's row does).
            .clearAndSetSemantics { }
            .padding(CARD_TEXT_PADDING),
        verticalArrangement = Arrangement.spacedBy(CARD_LINE_SPACING)
    ) {
        cardLines(item, title, isHighlighted).forEach { line ->
            Text(
                text = line.text,
                style = line.style,
                color = line.color,
                maxLines = line.maxLines,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
