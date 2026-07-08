package com.tortugapower.audiobookplayer.wear.glance

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.datalayer.WatchRemoteCodec
import com.tortugapower.audiobookplayer.datalayer.WearDataLayer
import com.tortugapower.audiobookplayer.logic.PlaybackManager
import com.tortugapower.audiobookplayer.wear.data.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resolves the [GlanceState] for the tile + complication from the same source, so both surfaces agree.
 * Mode-aware: STANDALONE (PRO) plays locally, so the live loaded item wins (falling back to the last-played
 * DB row, both with progress). REMOTE (free) plays on the phone — the watch's local player is a stale
 * process singleton there, so it's ignored in favor of the phone's published now-playing.
 */
object NowPlayingGlance {

    suspend fun resolve(context: Context): GlanceState = withContext(Dispatchers.IO) {
        val db = AppDatabase.getDatabase(context)
        val isPro = db.accountDao().getAccount()?.tier == AccountTier.PRO
        if (isPro) {
            val current = PlaybackManager.currentItem.value
            if (current != null) {
                // Live values (not the load-time snapshot on the entity): whole-book progress for the gauge
                // (shared formula, so it can't drift from the phone publisher) + the current whole-book chapter
                // index (0-based → 1-based label).
                val chapter = PlaybackManager.currentChapterIndex.value.takeIf { it >= 0 }?.plus(1)
                GlanceState.from(current).copy(
                    progress = PlaybackManager.wholeBookProgress(),
                    chapterNumber = chapter,
                )
            } else {
                GlanceState.from(db.libraryDao().getRecentPlayedItemsSync(1).firstOrNull())
            }
        } else {
            // One DataItems read for both the library (title/author) and playback (progress/chapter) items.
            val items = readPublishedItems(context)
            GlanceState.fromRemote(items.first, items.second)
        }
    }

    /** One-shot read of the phone's latest published library + playback DataItems (nulls if unavailable). */
    private suspend fun readPublishedItems(
        context: Context,
    ): Pair<com.tortugapower.audiobookplayer.datalayer.WatchLibraryState?, com.tortugapower.audiobookplayer.datalayer.WatchPlaybackState?> =
        try {
            val items = Wearable.getDataClient(context).getDataItems().await()
            try {
                fun payload(path: String) = items.firstOrNull { it.uri.path == path }
                    ?.let { DataMapItem.fromDataItem(it).dataMap.getByteArray(WearDataLayer.KEY_PAYLOAD) }
                val library = payload(WearDataLayer.PATH_LIBRARY_STATE)?.let(WatchRemoteCodec::decodeLibraryState)
                val playback = payload(WearDataLayer.PATH_PLAYBACK_STATE)?.let(WatchRemoteCodec::decodePlaybackState)
                library to playback
            } finally {
                items.release()
            }
        } catch (e: Exception) {
            // Fail soft to the empty state, but leave a breadcrumb so a DataClient/decode failure is diagnosable.
            Log.w("NowPlayingGlance", "Failed to read published state", e)
            null to null
        }
}
