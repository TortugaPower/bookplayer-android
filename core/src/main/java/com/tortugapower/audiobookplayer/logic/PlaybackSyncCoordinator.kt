package com.tortugapower.audiobookplayer.logic

import android.content.Context
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity

/**
 * What the sync engine needs from the target's player for cross-device last-played reconciliation
 * (FetchContentsProcessor): whether it's currently playing, the current item, and a way to restore a
 * more-recent server state. Implemented per-target — :app wraps PlaybackManager; a headless/no-player
 * context passes null (reconciliation is simply skipped).
 */
interface PlaybackSyncCoordinator {
    fun isPlaying(): Boolean
    fun currentItem(): LibraryItemEntity?
    fun syncLastPlayed(context: Context, item: LibraryItemEntity)
}
