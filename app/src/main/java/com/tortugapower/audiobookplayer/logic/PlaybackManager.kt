package com.tortugapower.audiobookplayer.logic

import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.tortugapower.audiobookplayer.MainActivity
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.repository.RoomLibraryRepository
import com.tortugapower.audiobookplayer.service.AudioPlayerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

object PlaybackManager {
    val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var controllerFuture: ListenableFuture<MediaController>? = null
    var player: Player? by mutableStateOf(null)
        private set

    var currentItem: LibraryItemEntity? by mutableStateOf(null)
        private set

    var hasNextItem by mutableStateOf(false)
        private set
    var hasPreviousItem by mutableStateOf(false)
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
    var isTransitioning by mutableStateOf(false)
    private var progressTrackerJob: kotlinx.coroutines.Job? = null

    fun initialize(context: Context) {
        if (player != null) return
        val appContext = context.applicationContext

        val sessionToken = SessionToken(appContext, ComponentName(appContext, AudioPlayerService::class.java))
        controllerFuture = MediaController.Builder(appContext, sessionToken).buildAsync()
        controllerFuture?.addListener({
            try {
                val mediaController = controllerFuture?.get() ?: return@addListener
                player = mediaController
                
                // Add listener once
                mediaController.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) {
                        isPlaying = playing
                        if (!playing) {
                            lastPauseTime = System.currentTimeMillis()
                            updateProgress(appContext)
                        } else {
                            if (smartRewindEnabled) {
                                applySmartRewind()
                            }
                            startProgressTracker(appContext)
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) {
                            updateProgress(appContext, forceFinished = true)
                            // Auto-play next item
                            scope.launch {
                                val current = currentItem ?: return@launch
                                val db = AppDatabase.getDatabase(appContext)
                                val repository = RoomLibraryRepository(db.libraryDao())
                                val nextItem = repository.getAdjacentItem(current.uuid, next = true)
                                if (nextItem != null) {
                                    playItem(appContext, nextItem)
                                }
                            }
                        } else if (playbackState == Player.STATE_READY && isTransitioning) {
                            isTransitioning = false
                        }
                    }
                })

                // Restore last played item after controller is ready
                scope.launch(Dispatchers.IO) {
                    val lastUuid = PlaybackSettingsManager.getLastItemUuid(appContext).first()
                    if (lastUuid != null) {
                        val db = AppDatabase.getDatabase(appContext)
                        val repository = RoomLibraryRepository(db.libraryDao())
                        val item = repository.getItemById(lastUuid)
                        if (item != null) {
                            val processedDir = File(appContext.filesDir, "Processed")
                            val file = File(processedDir, item.relativePath ?: "")
                            if (file.exists()) {
                                // Update navigation states
                                val next = repository.getAdjacentItem(item.uuid, next = true) != null
                                val prev = repository.getAdjacentItem(item.uuid, next = false) != null
                                launch(Dispatchers.Main) {
                                    hasNextItem = next
                                    hasPreviousItem = prev
                                }
                                launch(Dispatchers.Main) {
                                    isTransitioning = true
                                    val mediaItem = MediaItem.Builder()
                                        .setUri(file.absolutePath)
                                        .setMediaId(item.uuid)
                                        .setMediaMetadata(
                                            MediaMetadata.Builder()
                                                .setTitle(item.title)
                                                .setArtist(item.author ?: "Unknown author")
                                                .build()
                                        )
                                        .build()
                                    
                                    // Use atomic setMediaItem with starting position to prevent race conditions
                                    mediaController.setMediaItem(mediaItem, (item.currentTime * 1000).toLong())
                                    mediaController.prepare()
                                    
                                    // Apply speed and volume
                                    mediaController.setPlaybackSpeed(playbackSpeed)
                                    applyVolume(volumeBoost, playbackVolume)
                                    
                                    // Finalize restoration
                                    currentItem = item
                                }
                            }
                        }
                    }
                }

                // Apply current speed and volume when player is ready
                mediaController.setPlaybackSpeed(playbackSpeed)
                applyVolume(volumeBoost, playbackVolume)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, MoreExecutors.directExecutor())

        // Consolidate settings observation on background thread
        scope.launch(Dispatchers.IO) {
            launch {
                PlaybackSettingsManager.getSpeed(appContext).collectLatest { speed ->
                    playbackSpeed = speed
                    launch(Dispatchers.Main) { player?.setPlaybackSpeed(speed) }
                }
            }
            launch {
                PlaybackSettingsManager.getRewindInterval(appContext).collectLatest { rewindInterval = it }
            }
            launch {
                PlaybackSettingsManager.getForwardInterval(appContext).collectLatest { forwardInterval = it }
            }
            launch {
                PlaybackSettingsManager.getSmartRewind(appContext).collectLatest { smartRewindEnabled = it }
            }
            launch {
                PlaybackSettingsManager.getSmartRewindLimit(appContext).collectLatest { smartRewindLimit = it }
            }
            launch {
                PlaybackSettingsManager.getVolumeBoost(appContext).collectLatest { boost ->
                    volumeBoost = boost
                    launch(Dispatchers.Main) { applyVolume(boost, playbackVolume) }
                }
            }
            launch {
                PlaybackSettingsManager.getVolume(appContext).collectLatest { volume ->
                    playbackVolume = volume
                    launch(Dispatchers.Main) { applyVolume(volumeBoost, volume) }
                }
            }
        }
    }

    private fun startProgressTracker(context: Context) {
        progressTrackerJob?.cancel()
        progressTrackerJob = scope.launch {
            while (isPlaying) {
                kotlinx.coroutines.delay(10000)
                if (isPlaying) {
                    updateProgress(context)
                }
            }
        }
    }

    private fun updateProgress(context: Context, itemToUpdate: LibraryItemEntity? = null, forceFinished: Boolean = false) {
        if (isTransitioning) return
        
        val item = itemToUpdate ?: currentItem ?: return
        val p = player ?: return
        
        // Safety: Only update if the player is actually on this item AND is actively ready or buffering.
        // During IDLE or ended states (unless forced), the player position is not reliable for progress saving.
        val isPlayerOnItem = p.currentMediaItem?.mediaId == item.uuid
        val isPlayerActive = p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING
        
        if (!isPlayerOnItem || (!isPlayerActive && !forceFinished)) return

        val currentPos = p.currentPosition / 1000.0
        val totalDuration = item.duration
        val isFinished = forceFinished || (currentPos >= totalDuration - 1.0 && totalDuration > 0)

        scope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(context)
            val repository = RoomLibraryRepository(db.libraryDao())
            repository.updateItemProgress(item.uuid, currentPos, isFinished)
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
        updateProgress(context, itemToUpdate = currentItem)
        
        if (item.isFinished) {
            item.currentTime = 0.0
            item.isFinished = false
            item.percentCompleted = 0.0
            scope.launch(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(context)
                db.libraryDao().updateItem(item)
            }
        }
        
        isTransitioning = true
        currentItem = item
        
        // Update navigation states
        scope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(context)
            val repository = RoomLibraryRepository(db.libraryDao())
            val next = repository.getAdjacentItem(item.uuid, next = true) != null
            val prev = repository.getAdjacentItem(item.uuid, next = false) != null
            launch(Dispatchers.Main) {
                hasNextItem = next
                hasPreviousItem = prev
            }
        }
        
        scope.launch {
            PlaybackSettingsManager.setLastItemUuid(context, item.uuid)
        }
        val processedDir = File(context.filesDir, "Processed")
        val file = File(processedDir, item.relativePath ?: "")
        
        if (file.exists()) {
            val mediaItem = MediaItem.Builder()
                .setUri(file.absolutePath)
                .setMediaId(item.uuid)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(item.title)
                        .setArtist(item.author ?: "Unknown author")
                        .build()
                )
                .build()
            
            player?.setMediaItem(mediaItem, (item.currentTime * 1000).toLong())
            player?.prepare()
            player?.play()
            showPlayerScreen = true
            // Re-apply speed and volume on new item
            player?.setPlaybackSpeed(playbackSpeed)
            applyVolume(volumeBoost, playbackVolume)
        }
    }

    fun playItemByPath(context: Context, path: String, autoplay: Boolean = true, showPlayer: Boolean = true) {
        updateProgress(context, itemToUpdate = currentItem)
        scope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(context)
            val item = db.libraryDao().getItemByPath(path)
            if (item != null) {
                launch(Dispatchers.Main) {
                    isTransitioning = true
                    currentItem = item
                    PlaybackSettingsManager.setLastItemUuid(context, item.uuid)
                    val processedDir = File(context.filesDir, "Processed")
                    val file = File(processedDir, item.relativePath ?: "")
                    
                    if (file.exists()) {
                        val mediaItem = MediaItem.Builder()
                            .setUri(file.absolutePath)
                            .setMediaId(item.uuid)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(item.title)
                                    .setArtist(item.author ?: "Unknown author")
                                    .build()
                            )
                            .build()
                        player?.setMediaItem(mediaItem, (item.currentTime * 1000).toLong())
                        player?.prepare()
                        if (autoplay) player?.play()
                        if (showPlayer) showPlayerScreen = true
                        player?.setPlaybackSpeed(playbackSpeed)
                        applyVolume(volumeBoost, playbackVolume)
                    }
                }
            }
        }
    }

    fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
            updateProgress(MainActivity.currentContext ?: return)
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
        val p = player ?: return
        p.seekTo(p.currentPosition + (forwardInterval * 1000L))
        updateProgress(MainActivity.currentContext ?: return)
    }

    fun seekBackward() {
        val p = player ?: return
        p.seekTo(p.currentPosition - (rewindInterval * 1000L))
        updateProgress(MainActivity.currentContext ?: return)
    }

    fun seekTo(positionMs: Long) {
        val p = player ?: return
        p.seekTo(positionMs)
        updateProgress(MainActivity.currentContext ?: return)
    }

    fun setPlaybackSpeed(context: Context, speed: Float) {
        scope.launch {
            PlaybackSettingsManager.setSpeed(context, speed)
            updateProgress(context)
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
