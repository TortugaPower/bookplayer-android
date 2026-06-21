package com.tortugapower.audiobookplayer.logic

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.repository.LibraryRepository
import com.tortugapower.audiobookplayer.repository.RoomLibraryRepository
import com.tortugapower.audiobookplayer.service.AudioPlayerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object PlaybackManager {
    val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var controllerFuture: ListenableFuture<MediaController>? = null
    var player: Player? = null
        private set

    private var repository: LibraryRepository? = null
    private var appContext: Context? = null

    private val _currentItem = MutableStateFlow<LibraryItemEntity?>(null)
    val currentItem: StateFlow<LibraryItemEntity?> = _currentItem.asStateFlow()

    private val _hasNextItem = MutableStateFlow(false)
    val hasNextItem: StateFlow<Boolean> = _hasNextItem.asStateFlow()
    private val _hasPreviousItem = MutableStateFlow(false)
    val hasPreviousItem: StateFlow<Boolean> = _hasPreviousItem.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playbackState = MutableStateFlow(Player.STATE_IDLE)
    val playbackState: StateFlow<Int> = _playbackState.asStateFlow()

    private val _showPlayerScreen = MutableStateFlow(false)
    val showPlayerScreen: StateFlow<Boolean> = _showPlayerScreen.asStateFlow()

    fun setShowPlayer(value: Boolean) { _showPlayerScreen.value = value }

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()
    private val _rewindInterval = MutableStateFlow(30)
    val rewindInterval: StateFlow<Int> = _rewindInterval.asStateFlow()
    private val _forwardInterval = MutableStateFlow(30)
    val forwardInterval: StateFlow<Int> = _forwardInterval.asStateFlow()
    private val _volumeBoost = MutableStateFlow(false)
    val volumeBoost: StateFlow<Boolean> = _volumeBoost.asStateFlow()
    private val _playbackVolume = MutableStateFlow(1.0f)
    val playbackVolume: StateFlow<Float> = _playbackVolume.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private var lastPauseTime: Long = 0
    private var smartRewindEnabled = true
    private var smartRewindLimit = 30
    private val _isTransitioning = MutableStateFlow(false)
    val isTransitioning: StateFlow<Boolean> = _isTransitioning.asStateFlow()
    private var progressTrackerJob: kotlinx.coroutines.Job? = null

    fun initialize(context: Context, libraryRepository: LibraryRepository) {
        if (player != null) return
        repository = libraryRepository
        val appContext = context.applicationContext
        this.appContext = appContext

        // Restart the position tracker whenever a UI collector (re)appears while playing, so it
        // re-enters the fast tick rate immediately instead of waiting out a slow background delay.
        scope.launch {
            _positionMs.subscriptionCount
                .map { it > 0 }
                .distinctUntilChanged()
                .collect { hasCollectors ->
                    if (hasCollectors && _isPlaying.value) {
                        this@PlaybackManager.appContext?.let { startProgressTracker(it) }
                    }
                }
        }

        val sessionToken = SessionToken(appContext, ComponentName(appContext, AudioPlayerService::class.java))
        controllerFuture = MediaController.Builder(appContext, sessionToken).buildAsync()
        controllerFuture?.addListener({
            try {
                val mediaController = controllerFuture?.get() ?: return@addListener
                player = mediaController
                
                // Add listener once
                mediaController.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) {
                        if (_isPlaying.value == playing) return
                        _isPlaying.value = playing
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
                            _positionMs.value = player?.currentPosition ?: 0L
                            updateProgress(appContext)
                        }
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        _playbackState.value = state
                        if (state == Player.STATE_ENDED) {
                            updateProgress(appContext, forceFinished = true)
                            // Auto-play next item
                            scope.launch {
                                val current = _currentItem.value ?: return@launch
                                val db = AppDatabase.getDatabase(appContext)
                                val repository = RoomLibraryRepository(db.libraryDao())
                                val nextItem = repository.getAdjacentItem(current.uuid, next = true)
                                if (nextItem != null) {
                                    playItem(appContext, nextItem)
                                }
                            }
                        } else if (state == Player.STATE_READY && _isTransitioning.value) {
                            _isTransitioning.value = false
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
                                _hasNextItem.value = next
                                _hasPreviousItem.value = prev
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
                                    _isTransitioning.value = true

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
                                    mediaController.setPlaybackSpeed(_playbackSpeed.value)
                                    applyVolume(_volumeBoost.value, _playbackVolume.value)

                                    // Finalize restoration
                                    _currentItem.value = item
                                    // Seed from the intended offset, not the live player: prepare() is
                                    // async so currentPosition is still 0 here (matches playItem).
                                    _positionMs.value = (targetOffset * 1000).toLong()
                                }
                            }
                        }
                    }
                }

                // Apply current speed and volume when player is ready
                mediaController.setPlaybackSpeed(_playbackSpeed.value)
                applyVolume(_volumeBoost.value, _playbackVolume.value)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, MoreExecutors.directExecutor())

        // Consolidate settings observation on background thread
        scope.launch(Dispatchers.IO) {
            launch {
                PlaybackSettingsManager.getSpeed(appContext).collectLatest { speed ->
                    _playbackSpeed.value = speed
                    launch(Dispatchers.Main) { player?.setPlaybackSpeed(speed) }
                }
            }
            launch {
                PlaybackSettingsManager.getRewindInterval(appContext).collectLatest { _rewindInterval.value = it }
            }
            launch {
                PlaybackSettingsManager.getForwardInterval(appContext).collectLatest { _forwardInterval.value = it }
            }
            launch {
                PlaybackSettingsManager.getSmartRewind(appContext).collectLatest { smartRewindEnabled = it }
            }
            launch {
                PlaybackSettingsManager.getSmartRewindLimit(appContext).collectLatest { smartRewindLimit = it }
            }
            launch {
                PlaybackSettingsManager.getVolumeBoost(appContext).collectLatest { boost ->
                    _volumeBoost.value = boost
                    launch(Dispatchers.Main) { applyVolume(boost, _playbackVolume.value) }
                }
            }
            launch {
                PlaybackSettingsManager.getVolume(appContext).collectLatest { volume ->
                    _playbackVolume.value = volume
                    launch(Dispatchers.Main) { applyVolume(_volumeBoost.value, volume) }
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
            var elapsed = 0L
            while (_isPlaying.value) {
                // Tick fast (smooth seek bar) only while something is actually collecting positionMs.
                // PlayerScreen's collectAsStateWithLifecycle unsubscribes when the Activity isn't
                // RESUMED (screen off / backgrounded — even with the player open), so subscriptionCount
                // is 0 then and we fall back to the ~10s DB-persistence cadence: no 500ms wake-ups with
                // no consumer. The subscriptionCount observer in initialize restarts this loop the
                // moment a collector reappears, so the fast rate resumes promptly.
                val step = if (_positionMs.subscriptionCount.value > 0) 500L else 10000L
                kotlinx.coroutines.delay(step)
                if (!_isPlaying.value) break
                // Emit the live position for the UI
                _positionMs.value = player?.currentPosition ?: 0L
                // Persist progress to the DB on the original ~10s cadence
                elapsed += step
                if (elapsed >= 10000) {
                    elapsed = 0
                    updateProgress(context)
                }
            }
        }
    }

    private fun updateProgress(context: Context, itemToUpdate: LibraryItemEntity? = null, forceFinished: Boolean = false) {
        if (_isTransitioning.value) return

        val item = itemToUpdate ?: _currentItem.value ?: return
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

    fun syncLastPlayed(context: Context, item: LibraryItemEntity) {
        if (_isPlaying.value || player == null) return

        // If it's the same item and very close position, skip to avoid unnecessary reloads
        val current = _currentItem.value
        if (current?.uuid == item.uuid && Math.abs(current.currentTime - item.currentTime) < 2.0) {
            return
        }

        android.util.Log.d("PlaybackManager", "🔄 Syncing last played item from remote: ${item.title} at ${item.currentTime}s")
        
        // We can reuse playItem but with autoplay = false
        // Actually, let's make playItem support an optional autoplay flag if it doesn't already
        // Wait, playItem always calls play(). I'll update playItem to accept an autoplay param.
        playItem(context, item, autoplay = false)
    }

    fun playItem(context: Context, item: LibraryItemEntity, autoplay: Boolean = true) {
        // If it's already playing the requested item, just show the player
        if (item.uuid == _currentItem.value?.uuid && player?.isPlaying == true) {
            _showPlayerScreen.value = true
            return
        }

        // Only update progress of the previous item if we're actually switching books
        if (_currentItem.value?.uuid != item.uuid) {
            updateProgress(context, itemToUpdate = _currentItem.value)
        }
        
        if (item.isFinished) {
            item.currentTime = 0.0
            item.isFinished = false
            item.percentCompleted = 0.0
            scope.launch(Dispatchers.IO) {
                getRepository(context).updateItemProgress(item.uuid, 0.0, false)
            }
        }
        
        _isTransitioning.value = true
        _currentItem.value = item
        // For BOUND books the per-chapter offset is seeded in the BOUND branch below (the UI adds the
        // chapter's cumulative start); only non-BOUND can use the whole-book currentTime directly.
        if (item.type != com.tortugapower.audiobookplayer.database.entities.ItemType.BOUND) {
            _positionMs.value = (item.currentTime * 1000).toLong()
        }

        // Update navigation states
        scope.launch(Dispatchers.IO) {
            val repo = getRepository(context)
            val next = repo.getAdjacentItem(item.uuid, next = true) != null
            val prev = repo.getAdjacentItem(item.uuid, next = false) != null
            launch(Dispatchers.Main) {
                _hasNextItem.value = next
                _hasPreviousItem.value = prev
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

                    // Try to extract artwork if missing
                    if (subItem.artworkURL == null && file.exists()) {
                        val artworkDir = File(context.filesDir, "Artworks")
                        if (!artworkDir.exists()) artworkDir.mkdirs()
                        val artworkFile = File(artworkDir, "${subItem.uuid}.jpg")
                        if (ArtworkManager.extractAndSaveArtwork(file, artworkFile)) {
                            subItem.artworkURL = artworkFile.absolutePath
                            withContext(Dispatchers.IO) {
                                repository?.updateItem(subItem)
                            }
                        }
                    }

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

                    // Seed the chapter-relative offset so the UI's (chapterStart + positionMs) is correct.
                    _positionMs.value = (targetOffset * 1000).toLong()
                    player?.setMediaItems(mediaItems, targetIndex, (targetOffset * 1000).toLong())
                    player?.prepare()
                    if (autoplay) {
                        player?.play()
                        _showPlayerScreen.value = true
                    }
                    player?.setPlaybackSpeed(_playbackSpeed.value)
                    applyVolume(_volumeBoost.value, _playbackVolume.value)
                }
            } else {
                val file = File(processedDir, item.relativePath ?: "")
                
                // Try to extract artwork if missing
                if (item.artworkURL == null && file.exists()) {
                    val artworkDir = File(context.filesDir, "Artworks")
                    if (!artworkDir.exists()) artworkDir.mkdirs()
                    val artworkFile = File(artworkDir, "${item.uuid}.jpg")
                    if (ArtworkManager.extractAndSaveArtwork(file, artworkFile)) {
                        item.artworkURL = artworkFile.absolutePath
                        withContext(Dispatchers.IO) {
                            repository?.updateItem(item)
                        }
                    }
                }

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
                    if (autoplay) {
                        player?.play()
                        _showPlayerScreen.value = true
                    }
                    player?.setPlaybackSpeed(_playbackSpeed.value)
                    applyVolume(_volumeBoost.value, _playbackVolume.value)
                }
            }
        }
    }

    fun playItemByPath(context: Context, path: String, autoplay: Boolean = true, showPlayer: Boolean = true) {
        updateProgress(context, itemToUpdate = _currentItem.value)
        scope.launch(Dispatchers.IO) {
            val item = getRepository(context).getItemByPath(path)
            if (item != null) {
                launch(Dispatchers.Main) {
                    playItem(context, item)
                    if (!autoplay) player?.pause()
                    if (!showPlayer) _showPlayerScreen.value = false
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
        p.seekTo(p.currentPosition + (_forwardInterval.value * 1000L))
    }

    fun seekBackward() {
        val p = player ?: return
        p.seekTo(p.currentPosition - (_rewindInterval.value * 1000L))
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
            val current = _currentItem.value ?: return@launch
            val nextItem = getRepository(context).getAdjacentItem(current.uuid, next = true)
            if (nextItem != null) {
                playItem(context, nextItem)
            }
        }
    }

    fun playPrevious(context: Context) {
        scope.launch {
            val current = _currentItem.value ?: return@launch
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
            PlaybackSettingsManager.setVolumeBoost(context, !_volumeBoost.value)
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
