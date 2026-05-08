package com.tortugapower.audiobookplayer.logic

import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.service.AudioPlayerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

object PlaybackManager {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var controllerFuture: ListenableFuture<MediaController>? = null
    var player: Player? by mutableStateOf(null)
        private set

    var currentItem: LibraryItemEntity? by mutableStateOf(null)
        private set

    var isPlaying by mutableStateOf(false)
        private set

    var showPlayerScreen by mutableStateOf(false)

    var playbackSpeed by mutableStateOf(1.0f)
    var rewindInterval by mutableStateOf(30)
    var forwardInterval by mutableStateOf(30)
    var volumeBoost by mutableStateOf(false)
    var playbackVolume by mutableStateOf(1.0f)

    private var lastPauseTime: Long = 0
    private var smartRewindEnabled = true
    private var smartRewindLimit = 30

    fun initialize(context: Context) {
        if (player != null) return

        val sessionToken = SessionToken(context, ComponentName(context, AudioPlayerService::class.java))
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture?.addListener({
            player = controllerFuture?.get()
            player?.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                    if (!playing) {
                        lastPauseTime = System.currentTimeMillis()
                    } else if (smartRewindEnabled) {
                        applySmartRewind()
                    }
                }
            })
            // Apply current speed and volume when player is ready
            player?.setPlaybackSpeed(playbackSpeed)
            applyVolume(volumeBoost, playbackVolume)
        }, MoreExecutors.directExecutor())

        // Observe settings
        scope.launch {
            PlaybackSettingsManager.getSpeed(context).collectLatest { speed ->
                playbackSpeed = speed
                player?.setPlaybackSpeed(speed)
            }
        }
        scope.launch {
            PlaybackSettingsManager.getRewindInterval(context).collectLatest { interval ->
                rewindInterval = interval
            }
        }
        scope.launch {
            PlaybackSettingsManager.getForwardInterval(context).collectLatest { interval ->
                forwardInterval = interval
            }
        }
        scope.launch {
            PlaybackSettingsManager.getSmartRewind(context).collectLatest { enabled ->
                smartRewindEnabled = enabled
            }
        }
        scope.launch {
            PlaybackSettingsManager.getSmartRewindLimit(context).collectLatest { limit ->
                smartRewindLimit = limit
            }
        }
        scope.launch {
            PlaybackSettingsManager.getVolumeBoost(context).collectLatest { boost ->
                volumeBoost = boost
                applyVolume(boost, playbackVolume)
            }
        }
        scope.launch {
            PlaybackSettingsManager.getVolume(context).collectLatest { volume ->
                playbackVolume = volume
                applyVolume(volumeBoost, volume)
            }
        }

        // Restore last played item
        scope.launch {
            val lastUuid = PlaybackSettingsManager.getLastItemUuid(context).first()
            if (lastUuid != null) {
                val db = com.tortugapower.audiobookplayer.database.AppDatabase.getDatabase(context)
                val repository = com.tortugapower.audiobookplayer.repository.RoomLibraryRepository(db.libraryDao())
                val item = repository.getItemById(lastUuid)
                if (item != null) {
                    currentItem = item
                    // Prepare the player with the item but don't play yet
                    val processedDir = File(context.filesDir, "Processed")
                    val file = File(processedDir, item.relativePath ?: "")
                    if (file.exists()) {
                        controllerFuture?.addListener({
                            val mediaItem = MediaItem.fromUri(file.absolutePath)
                            player?.setMediaItem(mediaItem)
                            player?.prepare()
                            // Re-apply speed and volume
                            player?.setPlaybackSpeed(playbackSpeed)
                            applyVolume(volumeBoost, playbackVolume)
                        }, MoreExecutors.directExecutor())
                    }
                }
            }
        }
    }

    private fun applyVolume(boost: Boolean, volume: Float) {
        // Player.setVolume only accepts 0.0 to 1.0.
        // Boost is handled by LoudnessEnhancer in AudioPlayerService.
        player?.volume = volume.coerceIn(0.0f, 1.0f)
    }

    private fun applySmartRewind() {
        if (lastPauseTime == 0L) return
        val pauseDuration = (System.currentTimeMillis() - lastPauseTime) / 1000 // in seconds
        if (pauseDuration < 2) return // No rewind for very short pauses

        val rewindSecs = (pauseDuration / 10 + 2).coerceAtMost(smartRewindLimit.toLong())
        player?.seekTo((player?.currentPosition ?: 0) - (rewindSecs * 1000L))
        lastPauseTime = 0
    }

    fun playItem(context: Context, item: LibraryItemEntity) {
        currentItem = item
        scope.launch {
            PlaybackSettingsManager.setLastItemUuid(context, item.uuid)
        }
        val processedDir = File(context.filesDir, "Processed")
        val file = File(processedDir, item.relativePath ?: "")
        
        if (file.exists()) {
            val mediaItem = MediaItem.fromUri(file.absolutePath)
            player?.setMediaItem(mediaItem)
            player?.prepare()
            player?.play()
            showPlayerScreen = true
            // Re-apply speed and volume on new item
            player?.setPlaybackSpeed(playbackSpeed)
            applyVolume(volumeBoost, playbackVolume)
        }
    }

    fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
        } else {
            if (p.playbackState == Player.STATE_IDLE) {
                p.prepare()
            } else if (p.playbackState == Player.STATE_ENDED) {
                p.seekTo(0)
            }
            p.play()
        }
    }

    fun seekForward() {
        player?.seekTo((player?.currentPosition ?: 0) + (forwardInterval * 1000L))
    }

    fun seekBackward() {
        player?.seekTo((player?.currentPosition ?: 0) - (rewindInterval * 1000L))
    }

    fun setPlaybackSpeed(context: Context, speed: Float) {
        scope.launch {
            PlaybackSettingsManager.setSpeed(context, speed)
        }
    }

    fun setPlaybackVolume(context: Context, volume: Float) {
        scope.launch {
            PlaybackSettingsManager.setVolume(context, volume)
        }
    }

    fun toggleVolumeBoost(context: Context) {
        scope.launch {
            PlaybackSettingsManager.setVolumeBoost(context, !volumeBoost)
        }
    }

    fun release() {
        controllerFuture?.let {
            MediaController.releaseFuture(it)
        }
        player = null
        controllerFuture = null
    }
}
