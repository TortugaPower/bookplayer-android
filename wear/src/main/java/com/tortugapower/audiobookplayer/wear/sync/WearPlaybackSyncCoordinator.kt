package com.tortugapower.audiobookplayer.wear.sync

import android.content.Context
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.logic.PlaybackManager
import com.tortugapower.audiobookplayer.logic.PlaybackSyncCoordinator

/**
 * Watch-target [PlaybackSyncCoordinator], backed by the shared [PlaybackManager] (mirrors the phone's
 * `PlaybackManagerSyncCoordinator`). Injected into the sync engine so `FetchContentsProcessor` performs the
 * same cross-device last-played reconciliation as iOS: after a contents fetch brings the server's
 * `lastItemPlayed`, re-arm the on-watch player to it (paused, only when not already playing) instead of
 * leaving the stale locally-restored book.
 */
object WearPlaybackSyncCoordinator : PlaybackSyncCoordinator {
    override fun isPlaying(): Boolean = PlaybackManager.isPlaying.value
    override fun currentItem(): LibraryItemEntity? = PlaybackManager.currentItem.value
    override fun syncLastPlayed(context: Context, item: LibraryItemEntity) =
        PlaybackManager.syncLastPlayed(context, item)
}
