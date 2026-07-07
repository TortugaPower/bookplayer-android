package com.tortugapower.audiobookplayer.service

import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.ListenableFuture
import com.tortugapower.audiobookplayer.logic.BoundTimeline
import com.tortugapower.audiobookplayer.logic.PlayableItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Session-facing wrapper around the real [ExoPlayer][androidx.media3.exoplayer.ExoPlayer] that makes the
 * OS media-notification / lock-screen scrubber match the **in-app player's context** and report the
 * **current chapter title**, even for chapters embedded within a single file (which fire no MediaItem
 * transition).
 *
 * The system scrubber reads the session player's window-relative `getCurrentPosition()`/`getDuration()`,
 * so this wrapper re-presents the wrapped player's real per-file playlist as one of three virtual
 * playlists ([Mode]):
 *  - **WHOLE_BOOK** (BOUND + book context): one whole-book window (duration + position across the whole
 *    book; drags mapped back to `(file, offset)`).
 *  - **CHAPTER** (any book with chapters + chapter context): one window PER CHAPTER, current index =
 *    current chapter, so the scrubber shows the current chapter's elapsed/duration (iOS
 *    `currentTimeInContext` / `durationTimeInContext` parity). Works for single files with embedded
 *    chapters and for BOUND books alike.
 *  - **PASSTHROUGH** (single book + book context, or a book with no chapters): keep the real playlist,
 *    only override the current window's title with the current chapter.
 *
 * The underlying ExoPlayer keeps its real playlist (gapless auto-advance). [PlaybackManager] inverts the
 * window coordinates back to whole-book with the SAME per-mode mapping, so the two stay in lockstep.
 * Chapter changes are observed via [chapterIndexFlow] (position-driven) → [invalidateState].
 */
class BookTimelinePlayer(
    private val wrapped: Player,
    private val timelineFlow: StateFlow<BoundTimeline?>,
    private val chapterContextFlow: StateFlow<Boolean>,
    private val playableFlow: StateFlow<PlayableItem?>,
    chapterIndexFlow: StateFlow<Int>,
    scope: CoroutineScope
) : ForwardingSimpleBasePlayer(wrapped) {

    init {
        // Re-publish whenever the timeline, context toggle, current chapter, or loaded book changes. The
        // wrapped player's own changes (position, play/pause, file transitions) already invalidate via base.
        scope.launch {
            combine(timelineFlow, chapterContextFlow, chapterIndexFlow, playableFlow) { _, _, _, _ -> Unit }
                .drop(1) // base state is already published at init; only react to subsequent changes
                .collect { invalidateState() }
        }
    }

    /** Whole-book ms at the wrapped (real) player's current position — the ground truth for all modes. */
    private fun currentAbsMs(timeline: BoundTimeline): Long =
        if (timeline.isEmpty) wrapped.currentPosition
        else timeline.toAbsoluteMs(wrapped.currentMediaItemIndex, wrapped.currentPosition)

    /** Title of the chapter at the wrapped player's current whole-book position, or null. */
    private fun currentChapterTitle(): String? {
        val playable = playableFlow.value ?: return null
        return playable.chapterAt(currentAbsMs(playable.timeline))?.title
    }

    override fun getState(): SimpleBasePlayer.State {
        val base = super.getState()
        val playable = playableFlow.value
        return when {
            // No chapters, or single book in book context: keep the real playlist, only retitle the window.
            playable == null || playable.chapters.isEmpty() -> passthroughState(base)
            chapterContextFlow.value -> chapterState(base, playable)          // per-chapter playlist
            playable.isBoundBook -> wholeBookState(base, playable.timeline)   // one whole-book window
            else -> passthroughState(base)                                    // single book, book context
        }
    }

    private fun passthroughState(base: SimpleBasePlayer.State): SimpleBasePlayer.State =
        currentChapterTitle()?.let { withCurrentWindowTitle(base, it) } ?: base

    /** BOUND + book context: collapse the per-file playlist into one whole-book window. */
    private fun wholeBookState(base: SimpleBasePlayer.State, timeline: BoundTimeline): SimpleBasePlayer.State {
        val totalMs = timeline.totalDurationMs
        val absMs = currentAbsMs(timeline).coerceIn(0L, totalMs)
        val bufferedAbsMs = timeline
            .toAbsoluteMs(wrapped.currentMediaItemIndex, wrapped.bufferedPosition)
            .coerceIn(absMs, totalMs)
        val speed = playbackSpeed(base)

        val baseMeta = wrapped.currentMediaItem?.mediaMetadata
        val displayTitle = currentChapterTitle() ?: baseMeta?.albumTitle ?: baseMeta?.title
        val metadata = (baseMeta ?: MediaMetadata.EMPTY).buildUpon().setTitle(displayTitle).build()
        val windowItem = (wrapped.currentMediaItem ?: MediaItem.EMPTY).buildUpon().setMediaMetadata(metadata).build()

        val window = SimpleBasePlayer.MediaItemData.Builder(BOOK_WINDOW_UID)
            .setMediaItem(windowItem)
            .setMediaMetadata(metadata)
            .setDurationUs(totalMs * 1000)
            .setIsSeekable(true)
            .build()

        return base.buildUpon()
            .setAvailableCommands(withoutItemNav(base))
            .setPlaylist(listOf(window))
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(SimpleBasePlayer.PositionSupplier.getExtrapolating(absMs, speed))
            .setContentBufferedPositionMs(SimpleBasePlayer.PositionSupplier.getConstant(bufferedAbsMs))
            .setTotalBufferedDurationMs(
                SimpleBasePlayer.PositionSupplier.getConstant((bufferedAbsMs - absMs).coerceAtLeast(0L))
            )
            .build()
    }

    /** Chapter context: one window per chapter, current index = current chapter (per-chapter scrubber). */
    private fun chapterState(base: SimpleBasePlayer.State, playable: PlayableItem): SimpleBasePlayer.State {
        val timeline = playable.timeline
        val absMs = currentAbsMs(timeline).coerceIn(0L, timeline.totalDurationMs)
        val here = timeline.chapterLocalOf(absMs)
        val chapterStartMs = absMs - here.positionMs
        val chapterDurMs = timeline.chapterDurationMs(here.chapterIndex)
        val bufferedAbsMs = timeline
            .toAbsoluteMs(wrapped.currentMediaItemIndex, wrapped.bufferedPosition)
            .coerceIn(absMs, timeline.totalDurationMs)
        // maxOf guards coerceIn against an empty range (min > max) for a degenerate 0-duration chapter.
        val bufferedChapterPos =
            (bufferedAbsMs - chapterStartMs).coerceIn(here.positionMs, maxOf(here.positionMs, chapterDurMs))
        val speed = playbackSpeed(base)

        // One window per chapter; current file's artwork/artist reused, title per chapter.
        val baseMeta = wrapped.currentMediaItem?.mediaMetadata ?: MediaMetadata.EMPTY
        val currentItem = wrapped.currentMediaItem ?: MediaItem.EMPTY
        val windows = playable.chapters.mapIndexed { i, ch ->
            val meta = baseMeta.buildUpon().setTitle(ch.title).build()
            SimpleBasePlayer.MediaItemData.Builder("$CHAPTER_WINDOW_UID_PREFIX$i")
                .setMediaItem(currentItem.buildUpon().setMediaMetadata(meta).build())
                .setMediaMetadata(meta)
                .setDurationUs(timeline.chapterDurationMs(i) * 1000)
                .setIsSeekable(true)
                .build()
        }

        return base.buildUpon()
            .setAvailableCommands(withoutItemNav(base))
            .setPlaylist(windows)
            .setCurrentMediaItemIndex(here.chapterIndex)
            .setContentPositionMs(SimpleBasePlayer.PositionSupplier.getExtrapolating(here.positionMs, speed))
            .setContentBufferedPositionMs(SimpleBasePlayer.PositionSupplier.getConstant(bufferedChapterPos))
            .setTotalBufferedDurationMs(
                SimpleBasePlayer.PositionSupplier.getConstant((bufferedChapterPos - here.positionMs).coerceAtLeast(0L))
            )
            .build()
    }

    private fun playbackSpeed(base: SimpleBasePlayer.State): Float {
        val playing = base.playWhenReady &&
            base.playbackState == Player.STATE_READY &&
            base.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE
        return if (playing) base.playbackParameters.speed else 0f
    }

    /** Virtual playlists are navigated by seeking, not track-skip; drop inter-item nav commands. */
    private fun withoutItemNav(base: SimpleBasePlayer.State): Player.Commands =
        Player.Commands.Builder()
            .addAll(base.availableCommands)
            .remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .build()

    /** Return [base] with the currently-playing window's metadata title replaced by [title]. */
    private fun withCurrentWindowTitle(base: SimpleBasePlayer.State, title: String): SimpleBasePlayer.State {
        val playlist = base.playlist
        val index = base.currentMediaItemIndex
        val current = playlist.getOrNull(index) ?: return base
        val meta = current.mediaMetadata ?: MediaMetadata.EMPTY
        if (meta.title?.toString() == title) return base
        val newItem = current.buildUpon()
            .setMediaMetadata(meta.buildUpon().setTitle(title).build())
            .build()
        val newPlaylist = ArrayList(playlist).apply { this[index] = newItem }
        return base.buildUpon().setPlaylist(newPlaylist).build()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val playable = playableFlow.value
        val timeline = playable?.timeline
        return when {
            playable == null || playable.chapters.isEmpty() || timeline == null ->
                super.handleSeek(mediaItemIndex, positionMs, seekCommand) // passthrough
            chapterContextFlow.value -> {
                // mediaItemIndex = target chapter, positionMs = offset within it; map to whole-book -> file.
                val whole = timeline.wholeBookOfChapter(mediaItemIndex, positionMs)
                    .coerceIn(0L, timeline.totalDurationMs)
                val local = timeline.toLocal(whole)
                super.handleSeek(local.mediaItemIndex, local.positionMs, Player.COMMAND_SEEK_TO_MEDIA_ITEM)
            }
            playable.isBoundBook -> {
                // positionMs is in the virtual whole-book window; map to (file, per-file offset).
                val local = timeline.toLocal(positionMs.coerceAtLeast(0L))
                super.handleSeek(local.mediaItemIndex, local.positionMs, Player.COMMAND_SEEK_TO_MEDIA_ITEM)
            }
            else -> super.handleSeek(mediaItemIndex, positionMs, seekCommand) // single book, book context
        }
    }

    companion object {
        private const val BOOK_WINDOW_UID = "bookplayer.whole-book-window"
        private const val CHAPTER_WINDOW_UID_PREFIX = "bookplayer.chapter-window."
    }
}
