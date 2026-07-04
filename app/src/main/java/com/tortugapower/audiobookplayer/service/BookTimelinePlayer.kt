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
 * Session-facing wrapper around the real [ExoPlayer][androidx.media3.exoplayer.ExoPlayer] that (1) makes
 * the OS media-notification / lock-screen scrubber show the **same context as the in-app player** for
 * BOUND books, and (2) reports the **current chapter's title** as the now-playing title — even for
 * chapters embedded within a single file, which fire no MediaItem transition.
 *
 * The system scrubber reads the session player's window-relative `getCurrentPosition()`/`getDuration()`.
 * A BOUND book is a Media3 playlist (one window per sub-book), so by default it shows only the current
 * file. This wrapper virtualizes:
 *  - **BOUND + book context**: collapse the per-file playlist into one whole-book window (duration +
 *    position across the whole book; scrubber drags mapped back to `(sub-book, offset)`).
 *  - **BOUND + chapter context** and **single books**: pass through, but still override the current
 *    window's title with the current chapter (iOS `setNowPlayingBookTitle` parity).
 *
 * The underlying ExoPlayer keeps its real playlist, preserving gapless auto-advance. Chapter changes are
 * observed via [chapterIndexFlow] (position-driven, so it advances across embedded chapters within a
 * file) which triggers [invalidateState] to re-publish the title.
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
        // Re-publish whenever the book timeline, context toggle, or CURRENT CHAPTER changes. The wrapped
        // player's own changes (position, play/pause, file transitions) already invalidate via the base.
        scope.launch {
            combine(timelineFlow, chapterContextFlow, chapterIndexFlow) { _, _, _ -> Unit }
                .drop(1) // base state is already published at init; only react to subsequent changes
                .collect { invalidateState() }
        }
    }

    /** True when we should present a single whole-book window instead of the real per-file playlist. */
    private fun isVirtualizing(): Boolean {
        val t = timelineFlow.value
        return t != null && !t.isEmpty && !chapterContextFlow.value
    }

    /** Title of the chapter at the wrapped player's current whole-book position, or null. */
    private fun currentChapterTitle(): String? {
        val playable = playableFlow.value ?: return null
        val timeline = timelineFlow.value
        val absMs = if (timeline != null && !timeline.isEmpty) {
            timeline.toAbsoluteMs(wrapped.currentMediaItemIndex, wrapped.currentPosition)
        } else {
            wrapped.currentPosition
        }
        return playable.chapterAt(absMs)?.title
    }

    override fun getState(): SimpleBasePlayer.State {
        val base = super.getState()
        val chapterTitle = currentChapterTitle()

        if (!isVirtualizing()) {
            // Single book / chapter context: keep the real playlist, but surface the current chapter title.
            return if (chapterTitle != null) withCurrentWindowTitle(base, chapterTitle) else base
        }
        val timeline = timelineFlow.value ?: return base

        // Book context for a BOUND book: collapse the per-file playlist into one whole-book window.
        val totalMs = timeline.totalDurationMs
        val absMs = timeline
            .toAbsoluteMs(wrapped.currentMediaItemIndex, wrapped.currentPosition)
            .coerceIn(0L, totalMs)
        val bufferedAbsMs = timeline
            .toAbsoluteMs(wrapped.currentMediaItemIndex, wrapped.bufferedPosition)
            .coerceIn(absMs, totalMs)
        val playing = base.playWhenReady &&
            base.playbackState == Player.STATE_READY &&
            base.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE
        val speed = if (playing) base.playbackParameters.speed else 0f

        // Title = current chapter; keep the book's artist/artwork (albumTitle holds the book title).
        val current = wrapped.currentMediaItem
        val baseMeta = current?.mediaMetadata
        val displayTitle = chapterTitle ?: baseMeta?.albumTitle ?: baseMeta?.title
        val metadata = (baseMeta ?: MediaMetadata.EMPTY).buildUpon().setTitle(displayTitle).build()
        val windowItem = (current ?: MediaItem.EMPTY).buildUpon().setMediaMetadata(metadata).build()

        val window = SimpleBasePlayer.MediaItemData.Builder(BOOK_WINDOW_UID)
            .setMediaItem(windowItem)
            .setMediaMetadata(metadata)
            .setDurationUs(totalMs * 1000)
            .setIsSeekable(true)
            .build()

        // One window: drop inter-item navigation commands so the reported state stays self-consistent.
        val commands = Player.Commands.Builder()
            .addAll(base.availableCommands)
            .remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .build()

        return base.buildUpon()
            .setAvailableCommands(commands)
            .setPlaylist(listOf(window))
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(SimpleBasePlayer.PositionSupplier.getExtrapolating(absMs, speed))
            .setContentBufferedPositionMs(SimpleBasePlayer.PositionSupplier.getConstant(bufferedAbsMs))
            .setTotalBufferedDurationMs(
                SimpleBasePlayer.PositionSupplier.getConstant((bufferedAbsMs - absMs).coerceAtLeast(0L))
            )
            .build()
    }

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
        val timeline = timelineFlow.value
        if (isVirtualizing() && timeline != null) {
            // positionMs is in the virtual whole-book window; map back to (sub-book, per-item offset).
            val local = timeline.toLocal(positionMs.coerceAtLeast(0L))
            return super.handleSeek(local.mediaItemIndex, local.positionMs, Player.COMMAND_SEEK_TO_MEDIA_ITEM)
        }
        return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
    }

    companion object {
        private const val BOOK_WINDOW_UID = "bookplayer.whole-book-window"
    }
}
