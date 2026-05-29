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
import com.tortugapower.audiobookplayer.repository.LibraryRepository
import com.tortugapower.audiobookplayer.repository.RoomLibraryRepository
import com.tortugapower.audiobookplayer.service.AudioPlayerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object PlaybackManager {
    val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var controllerFuture: ListenableFuture<MediaController>? = null
    var player: Player? by mutableStateOf(null)
        private set

    private var repository: LibraryRepository? = null

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

    fun initialize(context: Context, libraryRepository: LibraryRepository) {
        if (player != null) return
        repository = libraryRepository
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

                    override fun onPositionDiscontinuity(
                        oldPosition: Player.PositionInfo,
                        newPosition: Player.PositionInfo,
                        reason: Int
                    ) {
                        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                            updateProgress(appContext)
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
                        val item = getRepository(appContext).getItemById(lastUuid)
                        if (item != null) {
                            val processedDir = File(appContext.filesDir, "Processed")
                            
                            // Update navigation states
                            val next = getRepository(appContext).getAdjacentItem(item.uuid, next = true) != null
                            val prev = getRepository(appContext).getAdjacentItem(item.uuid, next = false) != null
                            launch(Dispatchers.Main) {
                                hasNextItem = next
                                hasPreviousItem = prev
                            }

                            val mediaItems = if (item.type == com.tortugapower.audiobookplayer.database.entities.ItemType.BOUND) {
                                val subItems = getRepository(appContext).getItemsInPathSync(item.relativePath ?: "")
                                subItems.filter { it.type == com.tortugapower.audiobookplayer.database.entities.ItemType.BOOK }.map { subItem ->
                                    val file = File(processedDir, subItem.relativePath ?: "")
                                    val uri = if (file.exists()) {
                                        android.util.Log.d("PlaybackManager", "📄 Restoration: Using local file for ${subItem.title}")
                                        android.net.Uri.fromFile(file)
                                    } else if (!subItem.remoteURL.isNullOrEmpty()) {
                                        android.util.Log.d("PlaybackManager", "🌐 Restoration: Using remote URL for ${subItem.title}")
                                        android.net.Uri.parse(subItem.remoteURL)
                                    } else {
                                        android.util.Log.w("PlaybackManager", "⚠️ Restoration: No source available for ${subItem.title}")
                                        android.net.Uri.EMPTY
                                    }

                                    MediaItem.Builder()
                                        .setMediaId(subItem.uuid)
                                        .setUri(uri)
                                        .setMediaMetadata(
                                            MediaMetadata.Builder()
                                                .setTitle(subItem.title)
                                                .setArtist(subItem.author ?: item.author ?: "Unknown author")
                                                .setArtworkUri(subItem.artworkURL?.let { 
                                                    if (it.startsWith("http")) android.net.Uri.parse(it) 
                                                    else android.net.Uri.fromFile(java.io.File(it)) 
                                                })
                                                .build()
                                        )
                                        .build()
                                }
                            } else {
                                val file = File(processedDir, item.relativePath ?: "")
                                val uri = if (file.exists()) {
                                    android.util.Log.d("PlaybackManager", "📄 Restoration: Using local file for ${item.title}")
                                    android.net.Uri.fromFile(file)
                                } else if (!item.remoteURL.isNullOrEmpty()) {
                                    android.util.Log.d("PlaybackManager", "🌐 Restoration: Using remote URL for ${item.title}")
                                    android.net.Uri.parse(item.remoteURL)
                                } else {
                                    android.util.Log.w("PlaybackManager", "⚠️ Restoration: No source available for ${item.title}")
                                    null
                                }

                                if (uri != null && uri != android.net.Uri.EMPTY) {
                                    listOf(MediaItem.Builder()
                                        .setUri(uri)
                                        .setMediaId(item.uuid)
                                        .setMediaMetadata(
                                            MediaMetadata.Builder()
                                                .setTitle(item.title)
                                                .setArtist(item.author ?: "Unknown author")
                                                .setArtworkUri(item.artworkURL?.let { 
                                                    if (it.startsWith("http")) android.net.Uri.parse(it) 
                                                    else android.net.Uri.fromFile(java.io.File(it)) 
                                                })
                                                .build()
                                        )
                                        .build())
                                } else emptyList()
                            }

                            if (mediaItems.isNotEmpty()) {
                                launch(Dispatchers.Main) {
                                    isTransitioning = true
                                    
                                    var targetIndex = 0
                                    var targetOffset = item.currentTime
                                    if (item.type == com.tortugapower.audiobookplayer.database.entities.ItemType.BOUND) {
                                        val subItems = withContext(Dispatchers.IO) {
                                            getRepository(appContext).getItemsInPathSync(item.relativePath ?: "")
                                        }
                                        val books = subItems.filter { it.type == com.tortugapower.audiobookplayer.database.entities.ItemType.BOOK }
                                        var cumulative = 0.0
                                        for (i in books.indices) {
                                            if (item.currentTime >= cumulative && item.currentTime < cumulative + books[i].duration) {
                                                targetIndex = i
                                                targetOffset = item.currentTime - cumulative
                                                break
                                            }
                                            cumulative += books[i].duration
                                        }
                                    }

                                    mediaController.setMediaItems(mediaItems, targetIndex, (targetOffset * 1000).toLong())
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

    private fun getRepository(context: Context): LibraryRepository {
        return repository ?: RoomLibraryRepository(AppDatabase.getDatabase(context).libraryDao())
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
        
        // Safety: Only update if the player is actually on an item in this context
        val isPlayerActive = p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING
        if (!isPlayerActive && !forceFinished) return

        val currentPos = if (item.type == com.tortugapower.audiobookplayer.database.entities.ItemType.BOUND) {
            var totalPos = p.currentPosition / 1000.0
            val currentIndex = p.currentMediaItemIndex
            
            // We need the chapters (sub-books) to calculate total position
            // Since this is called frequently, we'll use a simplified check or assume the caller handles it.
            // For now, let's just use the current position if we can't easily get cumulative start.
            // Actually, let's just get the items in path sync.
            scope.launch(Dispatchers.IO) {
                val repo = getRepository(context)
                val subItems = repo.getItemsInPathSync(item.relativePath ?: "")
                val books = subItems.filter { it.type == com.tortugapower.audiobookplayer.database.entities.ItemType.BOOK }
                if (currentIndex >= 0 && currentIndex < books.size) {
                    var cumulativeStart = 0.0
                    for (i in 0 until currentIndex) {
                        cumulativeStart += books[i].duration
                    }
                    val finalTotalPos = cumulativeStart + totalPos
                    repo.updateItemProgress(item.uuid, finalTotalPos, forceFinished || (finalTotalPos >= item.duration - 1.0 && item.duration > 0))
                }
            }
            return // handled in scope
        } else {
            p.currentPosition / 1000.0
        }

        val totalDuration = item.duration
        val isFinished = forceFinished || (currentPos >= totalDuration - 1.0 && totalDuration > 0)

        scope.launch(Dispatchers.IO) {
            getRepository(context).updateItemProgress(item.uuid, currentPos, isFinished)
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
            val repo = getRepository(context)
            val next = repo.getAdjacentItem(item.uuid, next = true) != null
            val prev = repo.getAdjacentItem(item.uuid, next = false) != null
            launch(Dispatchers.Main) {
                hasNextItem = next
                hasPreviousItem = prev
            }
        }
        
        scope.launch {
            PlaybackSettingsManager.setLastItemUuid(context, item.uuid)
        }
        val processedDir = File(context.filesDir, "Processed")
        
        scope.launch(Dispatchers.Main) {
            if (item.type == com.tortugapower.audiobookplayer.database.entities.ItemType.BOUND) {
                val subItems = withContext(Dispatchers.IO) {
                    getRepository(context).getItemsInPathSync(item.relativePath ?: "")
                }
                val books = subItems.filter { it.type == com.tortugapower.audiobookplayer.database.entities.ItemType.BOOK }
                val mediaItems = books.map { subItem ->
                    val file = File(processedDir, subItem.relativePath ?: "")
                    val uri = if (file.exists()) {
                        android.util.Log.d("PlaybackManager", "📄 Playback: Using local file for ${subItem.title}")
                        android.net.Uri.fromFile(file)
                    } else if (!subItem.remoteURL.isNullOrEmpty()) {
                        android.util.Log.d("PlaybackManager", "🌐 Playback: Using remote URL for ${subItem.title}")
                        android.net.Uri.parse(subItem.remoteURL)
                    } else {
                        android.util.Log.w("PlaybackManager", "⚠️ Playback: No source available for ${subItem.title}")
                        android.net.Uri.EMPTY
                    }

                    MediaItem.Builder()
                        .setMediaId(subItem.uuid)
                        .setUri(uri)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(subItem.title)
                                .setArtist(subItem.author ?: item.author ?: "Unknown author")
                                .setArtworkUri(subItem.artworkURL?.let { 
                                    if (it.startsWith("http")) android.net.Uri.parse(it) 
                                    else android.net.Uri.fromFile(java.io.File(it)) 
                                })
                                .build()
                        )
                        .build()
                }

                if (mediaItems.isNotEmpty()) {
                    // Find correct sub-book and position
                    var targetIndex = 0
                    var targetOffset = item.currentTime
                    var cumulative = 0.0
                    for (i in books.indices) {
                        if (item.currentTime >= cumulative && item.currentTime < cumulative + books[i].duration) {
                            targetIndex = i
                            targetOffset = item.currentTime - cumulative
                            break
                        }
                        cumulative += books[i].duration
                    }

                    player?.setMediaItems(mediaItems, targetIndex, (targetOffset * 1000).toLong())
                    player?.prepare()
                    player?.play()
                    showPlayerScreen = true
                    player?.setPlaybackSpeed(playbackSpeed)
                    applyVolume(volumeBoost, playbackVolume)
                }
            } else {
                val file = File(processedDir, item.relativePath ?: "")
                val uri = if (file.exists()) {
                    android.util.Log.d("PlaybackManager", "📄 Playback: Using local file for ${item.title}")
                    android.net.Uri.fromFile(file)
                } else if (!item.remoteURL.isNullOrEmpty()) {
                    android.util.Log.d("PlaybackManager", "🌐 Playback: Using remote URL for ${item.title}")
                    android.net.Uri.parse(item.remoteURL)
                } else {
                    android.util.Log.w("PlaybackManager", "⚠️ Playback: No source available for ${item.title}")
                    null
                }

                if (uri != null && uri != android.net.Uri.EMPTY) {
                    val mediaItem = MediaItem.Builder()
                        .setUri(uri)
                        .setMediaId(item.uuid)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(item.title)
                                .setArtist(item.author ?: "Unknown author")
                                .setArtworkUri(item.artworkURL?.let { 
                                    if (it.startsWith("http")) android.net.Uri.parse(it) 
                                    else android.net.Uri.fromFile(java.io.File(it)) 
                                })
                                .build()
                        )
                        .build()
                    
                    player?.setMediaItem(mediaItem, (item.currentTime * 1000).toLong())
                    player?.prepare()
                    player?.play()
                    showPlayerScreen = true
                    player?.setPlaybackSpeed(playbackSpeed)
                    applyVolume(volumeBoost, playbackVolume)
                }
            }
        }
    }

    fun playItemByPath(context: Context, path: String, autoplay: Boolean = true, showPlayer: Boolean = true) {
        updateProgress(context, itemToUpdate = currentItem)
        scope.launch(Dispatchers.IO) {
            val item = getRepository(context).getItemByPath(path)
            if (item != null) {
                launch(Dispatchers.Main) {
                    playItem(context, item)
                    if (!autoplay) player?.pause()
                    if (!showPlayer) showPlayerScreen = false
                }
            }
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
        val p = player ?: return
        p.seekTo(p.currentPosition + (forwardInterval * 1000L))
    }

    fun seekBackward() {
        val p = player ?: return
        p.seekTo(p.currentPosition - (rewindInterval * 1000L))
    }

    fun seekTo(positionMs: Long) {
        val p = player ?: return
        p.seekTo(positionMs)
    }

    fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        val p = player ?: return
        p.seekTo(mediaItemIndex, positionMs)
    }

    fun playNext(context: Context) {
        scope.launch {
            val current = currentItem ?: return@launch
            val nextItem = getRepository(context).getAdjacentItem(current.uuid, next = true)
            if (nextItem != null) {
                playItem(context, nextItem)
            }
        }
    }

    fun playPrevious(context: Context) {
        scope.launch {
            val current = currentItem ?: return@launch
            val prevItem = getRepository(context).getAdjacentItem(current.uuid, next = false)
            if (prevItem != null) {
                playItem(context, prevItem)
            }
        }
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
