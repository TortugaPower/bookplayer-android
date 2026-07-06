package com.tortugapower.audiobookplayer.logic

import android.content.Context
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity

/** Phone-target [PlaybackSyncCoordinator], backed by the app's [PlaybackManager]. */
object PlaybackManagerSyncCoordinator : PlaybackSyncCoordinator {
    override fun isPlaying(): Boolean = PlaybackManager.isPlaying.value
    override fun currentItem(): LibraryItemEntity? = PlaybackManager.currentItem.value
    override fun syncLastPlayed(context: Context, item: LibraryItemEntity) =
        PlaybackManager.syncLastPlayed(context, item)
}
