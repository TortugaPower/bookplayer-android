package com.tortugapower.audiobookplayer.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.tortugapower.audiobookplayer.database.dao.LibraryDao
import com.tortugapower.audiobookplayer.datalayer.WatchPlaybackState
import com.tortugapower.audiobookplayer.datalayer.WatchRemoteCodec
import com.tortugapower.audiobookplayer.datalayer.WearDataLayer
import com.tortugapower.audiobookplayer.logic.PlaybackManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Phone side of remote-controller mode: mirrors [PlaybackManager] state to the watch over the Wear Data
 * Layer as two `DataClient` items — the rarely-changing library snapshot ([WearDataLayer.PATH_LIBRARY_STATE])
 * and the volatile playback state ([WearDataLayer.PATH_PLAYBACK_STATE]) — so play/pause only re-sends the
 * tiny playback item, never the recent list. The playback item also carries the play/pause echo (the phone's
 * true state for any cause). Started once from `BookPlayerApplication`.
 */
object WearRemotePublisher {
    private const val TAG = "WearRemotePublisher"
    private const val RECENT_LIMIT = 20

    private lateinit var appContext: Context
    private lateinit var libraryDao: LibraryDao
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dataClient by lazy { Wearable.getDataClient(appContext) }

    fun initialize(context: Context, libraryDao: LibraryDao) {
        appContext = context.applicationContext
        this.libraryDao = libraryDao
    }

    @kotlin.OptIn(kotlinx.coroutines.FlowPreview::class)
    fun start() {
        // Library item — rebuild when the recent set changes (a contents sync writing lastPlayDate, so
        // server-synced plays appear on the watch without waiting for local playback/refresh), on the
        // current item changing, or a skip-interval change. Debounced so a sync burst coalesces into one
        // publish (DataClient also dedupes byte-identical items).
        scope.launch {
            combine(
                libraryDao.getRecentPlayedItems(RECENT_LIMIT),
                PlaybackManager.currentPlayable,
                PlaybackManager.rewindInterval,
                PlaybackManager.forwardInterval,
            ) { _, _, _, _ -> Unit }
                .debounce(300)
                .collect { publishLibrary() }
        }
        // Playback item — rebuild on play/pause, speed, boost, or CHAPTER change (deduped). Progress is
        // sampled at each of these events (not a continuous source, so no per-tick spam) — enough for the
        // watch glance surfaces, which also re-request on wrist-raise.
        scope.launch {
            combine(
                PlaybackManager.isPlaying,
                PlaybackManager.playbackSpeed,
                PlaybackManager.volumeBoost,
                PlaybackManager.currentChapterIndex,
            ) { playing, speed, boost, chapter ->
                WatchPlaybackState(
                    playing, speed, boost,
                    progress = PlaybackManager.wholeBookProgress(),
                    currentChapter = chapterLabel(chapter),
                )
            }
                .distinctUntilChanged()
                .collect { publishPlayback(it) }
        }
    }

    /** 1-based current chapter for the watch (0 = unknown), from the 0-based whole-book index. */
    private fun chapterLabel(index: Int): Int = if (index >= 0) index + 1 else 0

    /** Force a re-publish of both items — the watch's REFRESH command. */
    fun refresh() {
        scope.launch {
            publishLibrary()
            publishPlayback(currentPlaybackState())
        }
    }

    private fun currentPlaybackState() = WatchPlaybackState(
        isPlaying = PlaybackManager.isPlaying.value,
        speed = PlaybackManager.playbackSpeed.value,
        boostVolume = PlaybackManager.volumeBoost.value,
        progress = PlaybackManager.wholeBookProgress(),
        currentChapter = chapterLabel(PlaybackManager.currentChapterIndex.value),
    )

    private suspend fun publishLibrary() {
        val state = WearStateBuilder.buildLibraryState(
            recent = libraryDao.getRecentPlayedItemsSync(RECENT_LIMIT),
            current = PlaybackManager.currentPlayable.value,
            rewindInterval = PlaybackManager.rewindInterval.value,
            forwardInterval = PlaybackManager.forwardInterval.value,
        )
        put(WearDataLayer.PATH_LIBRARY_STATE, WatchRemoteCodec.encodeLibraryState(state))
    }

    private fun publishPlayback(state: WatchPlaybackState) {
        put(WearDataLayer.PATH_PLAYBACK_STATE, WatchRemoteCodec.encodePlaybackState(state))
    }

    // DataClient dedupes byte-identical items, so re-publishing unchanged state is a cheap no-op. Blocking
    // await is fine on this IO scope.
    private fun put(path: String, payload: ByteArray) {
        val request = PutDataMapRequest.create(path).apply {
            dataMap.putByteArray(WearDataLayer.KEY_PAYLOAD, payload)
        }.asPutDataRequest().setUrgent()
        try {
            Tasks.await(dataClient.putDataItem(request))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to publish $path to watch", e)
        }
    }
}
