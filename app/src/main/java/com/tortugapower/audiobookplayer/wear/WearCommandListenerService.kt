package com.tortugapower.audiobookplayer.wear

import android.os.Handler
import android.os.Looper
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.datalayer.WatchRemoteCodec
import com.tortugapower.audiobookplayer.datalayer.WearDataLayer
import com.tortugapower.audiobookplayer.logic.PlaybackManager
import com.tortugapower.audiobookplayer.logic.SleepTimerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Receives watch→phone control commands over the Data Layer ([WearDataLayer.PATH_COMMAND]) and drives the
 * phone's [PlaybackManager] — the Android analog of iOS's `ActionParserService`. Routing is the pure
 * [WearCommandMapper]; this class is the real-effect [RemotePlaybackActions] impl.
 *
 * Commands run on the main thread: [PlaybackManager] mutates ExoPlayer directly, which is main-thread-only
 * (the one exception, `playItemByPath`, self-marshals). `onMessageReceived` is a binder-thread callback, so
 * we post the dispatch to the main looper.
 */
class WearCommandListenerService : WearableListenerService(), RemotePlaybackActions {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WearDataLayer.PATH_COMMAND) return
        val command = WatchRemoteCodec.decodeCommand(event.data) ?: return
        mainHandler.post { WearCommandMapper.dispatch(command, this) }
    }

    override fun onDestroy() {
        // Tear down any in-flight PLAY resolution coroutine when the system destroys the service.
        scope.cancel()
        super.onDestroy()
    }

    override fun play(itemId: String?) {
        val current = PlaybackManager.currentItem.value
        // Resume the current item (matches iOS "play with the loaded identifier -> play()").
        if (itemId == null || itemId == current?.relativePath || itemId == current?.uuid) {
            if (current != null && !PlaybackManager.isPlaying.value) PlaybackManager.togglePlayPause()
            return
        }
        // A different item: resolve by path (then uuid) off-main, load on main.
        scope.launch {
            val dao = AppDatabase.getDatabase(applicationContext).libraryDao()
            val item = dao.getItemByPath(itemId) ?: dao.getItemById(itemId)
            if (item != null) withContext(Dispatchers.Main) { PlaybackManager.playItem(applicationContext, item) }
        }
    }

    override fun pause() = PlaybackManager.pause()
    override fun skipForward() = PlaybackManager.seekForward()
    override fun skipBackward() = PlaybackManager.seekBackward()
    override fun seekToChapter(startSeconds: Double) =
        PlaybackManager.seekWholeBook((startSeconds * 1000).toLong())

    override fun setSpeed(speed: Float) = PlaybackManager.setPlaybackSpeed(applicationContext, speed)
    override fun sleepOff() = SleepTimerManager.stopTimer()
    override fun sleepEndOfChapter() = SleepTimerManager.startTimerUntilEndOfChapter()
    override fun sleepAfter(seconds: Long) = SleepTimerManager.startTimerMillis(seconds * 1000)

    override fun setBoost(on: Boolean) {
        if (on != PlaybackManager.volumeBoost.value) PlaybackManager.toggleVolumeBoost(applicationContext)
    }

    override fun refresh() = WearRemotePublisher.refresh()
}
