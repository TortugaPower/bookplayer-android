package com.tortugapower.audiobookplayer.service

import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.ListenableFuture
import com.tortugapower.audiobookplayer.logic.BoundTimeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Session-facing wrapper around the real [ExoPlayer][androidx.media3.exoplayer.ExoPlayer] that makes
 * the OS media-notification / lock-screen scrubber show the **same context as the in-app player** for
 * BOUND books — without a custom notification.
 *
 * The system scrubber reads the session player's window-relative `getCurrentPosition()`/`getDuration()`.
 * A BOUND book is loaded as a Media3 playlist (one window per sub-book), so by default the scrubber
 * shows only the current file. This wrapper virtualizes the player's exposed state:
 *
 *  - **BOUND + book context** (the default): collapse the per-file playlist into a single virtual
 *    window whose duration is the whole book and whose position is the cumulative position. Scrubber
 *    drags arrive in whole-book coordinates and are mapped back to `(sub-book, offset)`.
 *  - **BOUND + chapter context**: pass the real per-file playlist through unchanged — the scrubber then
 *    shows the current sub-book, which is exactly the current chapter (already correct).
 *  - **single BOOK**: pass through (already whole-book).
 *
 * This is the Android analogue of iOS hand-populating `MPNowPlayingInfoCenter` (iOS's lock screen
 * likewise does not read the AVPlayer directly). The underlying ExoPlayer keeps its real playlist, so
 * gapless auto-advance and cross-file pre-buffering are preserved.
 *
 * The [timelineFlow] (current BOUND book's chapters) and [chapterContextFlow] (the user's toggle) come
 * from `PlaybackManager`; a change in either re-publishes the session state via [invalidateState].
 */
class BookTimelinePlayer(
    private val wrapped: Player,
    private val timelineFlow: StateFlow<BoundTimeline?>,
    private val chapterContextFlow: StateFlow<Boolean>,
    scope: CoroutineScope
) : ForwardingSimpleBasePlayer(wrapped) {

    init {
        // Re-publish whenever the book timeline or context toggle changes. The wrapped player's own
        // changes (position, play/pause, transitions) already invalidate via the base class.
        scope.launch {
            combine(timelineFlow, chapterContextFlow) { _, _ -> Unit }
                .drop(1) // base state is already published at init; only react to subsequent changes
                .collect { invalidateState() }
        }
    }

    /** True when we should present a single whole-book window instead of the real per-file playlist. */
    private fun isVirtualizing(): Boolean {
        val t = timelineFlow.value
        return t != null && !t.isEmpty && !chapterContextFlow.value
    }

    override fun getState(): SimpleBasePlayer.State {
        val base = super.getState()
        if (!isVirtualizing()) return base // single book or chapter context: per-file pass-through
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

        // Show the book title (stored as albumTitle on each sub-book MediaItem), keeping artist/artwork.
        val current = wrapped.currentMediaItem
        val metadata = current?.mediaMetadata?.let { md ->
            md.buildUpon().setTitle(md.albumTitle ?: md.title).build()
        } ?: MediaMetadata.EMPTY
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
