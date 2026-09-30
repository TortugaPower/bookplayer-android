package com.tortugapower.audiobookplayer.service

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.logic.PlayableItem
import com.tortugapower.audiobookplayer.logic.PlayableItemBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The session-side [BookTimelinePlayer] must expose the REAL player's tracks on the window it says
 * is playing, in every mode. The in-app player screen decides "this is a video" from the
 * controller's current tracks; the whole-book and chapter windows used to be built without tracks,
 * so once chapter context became the default every video played as audio (no surface, no
 * fullscreen control). Passthrough already inherited them from the forwarding base.
 */
@RunWith(RobolectricTestRunner::class)
class BookTimelinePlayerTracksTest {

    private val videoTracks = Tracks(
        listOf(
            Tracks.Group(
                TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build()),
                /* adaptiveSupported = */ false,
                intArrayOf(C.FORMAT_HANDLED),
                booleanArrayOf(true)
            ),
            Tracks.Group(
                TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build()),
                false,
                intArrayOf(C.FORMAT_HANDLED),
                booleanArrayOf(true)
            )
        )
    )

    /** A real Player with one 100 s item that reports [videoTracks] — the ExoPlayer stand-in. */
    private inner class FakeFilePlayer(private val items: Int) : SimpleBasePlayer(Looper.getMainLooper()) {
        override fun getState(): State = State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder()
                    .addAll(
                        Player.COMMAND_GET_TIMELINE,
                        Player.COMMAND_GET_TRACKS,
                        Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_GET_METADATA,
                        Player.COMMAND_PLAY_PAUSE,
                        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                    )
                    .build()
            )
            .setPlaylist(
                (0 until items).map { i ->
                    MediaItemData.Builder("file-$i")
                        .setMediaItem(MediaItem.fromUri("file:///book/$i.mp4"))
                        .setDurationUs(100_000_000L)
                        .setIsSeekable(true)
                        .setTracks(if (i == 0) videoTracks else Tracks.EMPTY)
                        .build()
                }
            )
            .setCurrentMediaItemIndex(0)
            .setPlaybackState(Player.STATE_READY)
            .build()
    }

    private fun entity(uuid: String, type: ItemType, duration: Double, path: String = uuid) = LibraryItemEntity(
        uuid = uuid, title = uuid, duration = duration, relativePath = path, type = type
    )

    private fun wrap(wrapped: Player, playable: PlayableItem, chapterContext: Boolean) = BookTimelinePlayer(
        wrapped = wrapped,
        timelineFlow = MutableStateFlow(playable.timeline),
        chapterContextFlow = MutableStateFlow(chapterContext),
        playableFlow = MutableStateFlow(playable),
        chapterIndexFlow = MutableStateFlow(0),
        scope = CoroutineScope(Dispatchers.Main)
    )

    private fun Tracks.hasVideo() = groups.any { it.type == C.TRACK_TYPE_VIDEO }

    @Test fun `chapter mode exposes the real player's tracks on the current chapter window`() {
        // A single file with no embedded chapters gets one synthetic chapter, so with chapter context
        // (the default) it goes through the chapter-window mode — the everyday video case.
        val playable = PlayableItemBuilder.buildSingle(entity("video", ItemType.BOOK, 100.0), emptyList())
        val player = wrap(FakeFilePlayer(items = 1), playable, chapterContext = true)

        assertEquals(1, player.mediaItemCount)
        assertTrue(player.currentTracks.hasVideo())
    }

    @Test fun `whole-book mode exposes the real player's tracks on the single window`() {
        val folder = entity("vol", ItemType.BOUND, 200.0)
        val subs = listOf(
            entity("a", ItemType.BOOK, 100.0, "vol/a.mp4"),
            entity("b", ItemType.BOOK, 100.0, "vol/b.mp4"),
        )
        val playable = PlayableItemBuilder.buildBound(folder, subs, emptyMap())
        val player = wrap(FakeFilePlayer(items = 2), playable, chapterContext = false)

        assertEquals(1, player.mediaItemCount)
        assertTrue(player.currentTracks.hasVideo())
    }

    @Test fun `passthrough mode still inherits the tracks`() {
        val playable = PlayableItemBuilder.buildSingle(entity("video", ItemType.BOOK, 100.0), emptyList())
        val player = wrap(FakeFilePlayer(items = 1), playable, chapterContext = false)

        assertTrue(player.currentTracks.hasVideo())
    }
}
