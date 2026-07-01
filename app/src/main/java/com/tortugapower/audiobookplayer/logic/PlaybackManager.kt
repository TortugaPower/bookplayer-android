package com.tortugapower.audiobookplayer.logic

import android.content.ComponentName
import android.appwidget.AppWidgetManager
import android.content.Intent
import com.tortugapower.audiobookplayer.widget.AudioWidgetProvider
import kotlinx.coroutines.flow.combine
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
import com.tortugapower.audiobookplayer.network.NetworkClient
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
import java.util.concurrent.ConcurrentHashMap

object PlaybackManager {
    val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var controllerFuture: ListenableFuture<MediaController>? = null
    var player: Player? = null
        private set

    // Playback headers for external media servers, keyed by the stream URL's authority (host:port).
    // Registered when playItem receives headers and seeded from the external_servers table on a
    // miss, so internal re-entries (auto-advance, next/previous) and session restore after process
    // death resolve the same auth as the original play without threading headers through every call
    // site. Keying by authority also pins the headers to their server's host: a request to any other
    // host (including a redirect target) resolves nothing.
    private val externalHostHeaders = ConcurrentHashMap<String, Map<String, String>>()
    @Volatile private var externalHeadersSeeded = false

    private fun hostKey(uri: android.net.Uri): String? = uri.authority?.lowercase()

    /** Register/refresh the playback headers for a server's host (e.g. on play or after re-auth). */
    fun registerHeadersForUri(uri: android.net.Uri, headers: Map<String, String>) {
        hostKey(uri)?.let { externalHostHeaders[it] = headers }
    }

    /**
     * The auth headers to attach to a playback request for [uri], or null if the URI doesn't belong
     * to a configured external server. Called from Media3's loading threads (never the main thread);
     * a miss seeds the map from the DB once per process, which covers restoring a persisted session
     * URI after process death.
     */
    fun getHeadersForUri(uri: android.net.Uri): Map<String, String>? {
        val key = hostKey(uri) ?: return null
        externalHostHeaders[key]?.let { return it }
        if (!externalHeadersSeeded) {
            val context = appContext ?: return null
            kotlinx.coroutines.runBlocking { seedExternalHostHeaders(context) }
        }
        return externalHostHeaders[key]
    }

    /** In-memory-only check (safe on the main thread): is [uri] a registered external-server host? */
    fun hasHeadersForUri(uri: android.net.Uri): Boolean =
        hostKey(uri)?.let { externalHostHeaders.containsKey(it) } ?: false

    // Set when a stream from an external server fails with 401/403 — the stored session died
    // mid-playback. The UI surfaces it as a plain error alert (no re-auth routing by design).
    private val _externalStreamAuthError = MutableStateFlow(false)
    val externalStreamAuthError: StateFlow<Boolean> = _externalStreamAuthError.asStateFlow()

    fun reportExternalStreamAuthError() {
        _externalStreamAuthError.value = true
    }

    fun clearExternalStreamAuthError() {
        _externalStreamAuthError.value = false
    }

    private suspend fun seedExternalHostHeaders(context: Context) {
        // Through the repository, not the DAO: stored credentials are encrypted at rest.
        val servers = com.tortugapower.audiobookplayer.repository.ExternalServerRepository(
            AppDatabase.getDatabase(context).externalServerDao()
        ).allServers.first()
        for (server in servers) {
            val headers = ExternalServiceUtils.playbackHeaders(server.type, server.token, server.customHeaders) ?: continue
            registerHeadersForUri(android.net.Uri.parse(server.url), headers)
        }
        externalHeadersSeeded = true
    }


    private var repository: LibraryRepository? = null
    private var appContext: Context? = null

    // --- Observable playback state, the app-scoped source of truth. Collect from Compose via
    // collectAsStateWithLifecycle; read `.value` from non-Compose code. Only PlaybackManager writes it. ---

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

    /**
     * Current playback position in WHOLE-BOOK ms — already inverted from the (possibly virtualized)
     * session window via [controllerToWholeBookMs], so consumers (PlayerScreen) use it directly with no
     * further mapping. Emitted while playing and re-seeded on seek/load; ticks fast only while a
     * collector is active (see [startProgressTracker]). 0 before anything plays.
     */
    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    // The whole-book timeline for the current BOUND book (null for a single BOOK). Built once when a
    // BOUND book is loaded (playItem / restore) so progress persistence and any per-item<->whole-book
    // conversion read from one cached source instead of re-reading sub-books from the DB each tick.
    // Exposed as a flow so the session-side BookTimelinePlayer can virtualize a whole-book timeline.
    private val _currentTimeline = MutableStateFlow<BoundTimeline?>(null)
    val currentTimeline: StateFlow<BoundTimeline?> = _currentTimeline.asStateFlow()

    // The in-memory playback model for the current book (chapters, whole-book times, metadata), built
    // once per load by PlayableItemBuilder. The single source for the chapter list and whole-book
    // position; null only before anything is loaded. (For a single BOOK, _currentTimeline stays null so
    // the session passes through, but _currentPlayable still carries the chapters for the UI.)
    private val _currentPlayable = MutableStateFlow<PlayableItem?>(null)
    val currentPlayable: StateFlow<PlayableItem?> = _currentPlayable.asStateFlow()

    // The current chapter index (whole-book), advancing even across embedded chapters WITHIN one file
    // (which fire no MediaItem transition). Drives the per-chapter Now Playing title. -1 when nothing
    // is loaded. PUSHED by refreshCurrentChapterIndex() from the progress tracker, seeks, and file
    // transitions — deliberately NOT a hot combine(_positionMs, …): an Eagerly/global-scope flow would
    // keep a permanent _positionMs collector, pinning subscriptionCount > 0 forever and defeating the
    // tick-rate battery optimization (see startProgressTracker). Updates therefore follow the tracker
    // cadence (fast foreground, slow when backgrounded) — the same ≤10s title lag we already accept.
    private val _currentChapterIndex = MutableStateFlow(-1)
    val currentChapterIndex: StateFlow<Int> = _currentChapterIndex.asStateFlow()

    private fun refreshCurrentChapterIndex() {
        val next = _currentPlayable.value?.chapterIndexAt(currentWholeBookMs()) ?: -1
        if (_currentChapterIndex.value != next) _currentChapterIndex.value = next
    }

    // Mirror of the user's chapter-vs-book context preference. The session-side player virtualizes a
    // whole-book window only when book context is active (false); chapter context passes the per-file
    // playlist through (the notification then shows the current chapter, which is already correct).
    private val _useChapterContext = MutableStateFlow(false)
    val useChapterContext: StateFlow<Boolean> = _useChapterContext.asStateFlow()

    private var lastPauseTime: Long = 0
    private var smartRewindEnabled = true
    private var smartRewindLimit = 30
    private val _isTransitioning = MutableStateFlow(false)
    val isTransitioning: StateFlow<Boolean> = _isTransitioning.asStateFlow()
    private var progressTrackerJob: kotlinx.coroutines.Job? = null
    // Wall-clock timestamp of the last DB progress persist. A field (not a per-loop accumulator) so the
    // ~10s persist cadence survives tracker restarts (the subscriptionCount observer restarts the loop).
    private var lastProgressPersistMs = 0L

    fun initialize(context: Context, libraryRepository: LibraryRepository) {
        if (player != null) return
        repository = libraryRepository
        val appContext = context.applicationContext
        this.appContext = appContext

        // Seed the external-server header map eagerly (off the main thread), so the runBlocking
        // fallback inside getHeadersForUri stays a cold-restore edge case rather than the norm.
        scope.launch(Dispatchers.IO) {
            try {
                seedExternalHostHeaders(appContext)
            } catch (e: Exception) {
                android.util.Log.w("PlaybackManager", "Failed to seed external host headers", e)
            }
        }
        
        scope.launch {
            combine(_currentItem, _isPlaying) { item, playing -> Pair(item, playing) }
                .collect {
                    appContext.let { ctx ->
                        val intent = Intent(ctx, AudioWidgetProvider::class.java).apply {
                            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                        }
                        val ids = AppWidgetManager.getInstance(ctx).getAppWidgetIds(
                            ComponentName(ctx, AudioWidgetProvider::class.java)
                        )
                        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                        ctx.sendBroadcast(intent)
                    }
                }
        }

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
                        StatisticsManager.setPlaybackState(appContext, _currentItem.value, playing)
                    }

                    override fun onPositionDiscontinuity(
                        oldPosition: Player.PositionInfo,
                        newPosition: Player.PositionInfo,
                        reason: Int
                    ) {
                        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                            _positionMs.value = currentWholeBookMs()
                            refreshCurrentChapterIndex()
                            updateProgress(appContext)
                        }
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        // A BOUND book's sub-book (chapter) boundary. Fire the end-of-chapter sleep timer
                        // exactly here (not the ≤1s poll) on natural advance or a manual skip; ignore the
                        // initial playlist load / repeat.
                        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                            reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
                        ) {
                            SleepTimerManager.onChapterBoundaryReached()
                        }
                        // A file boundary can also be a chapter boundary (classic one-file-per-chapter
                        // BOUND); refresh so the Now Playing title updates immediately, not next tick.
                        refreshCurrentChapterIndex()
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        _playbackState.value = state
                        if (state == Player.STATE_ENDED) {
                            updateProgress(appContext, forceFinished = true)
                            StatisticsManager.setPlaybackState(appContext, _currentItem.value, false)
                            if (SleepTimerManager.isEndOfChapter) {
                                // End-of-chapter armed on the last chapter: stop here, don't roll into
                                // the next book (iOS's .bookEnd + autoplay=false safeguard).
                                SleepTimerManager.onBookEnded()
                            } else {
                                // Auto-play next item
                                scope.launch {
                                    val current = _currentItem.value ?: return@launch
                                    val db = AppDatabase.getDatabase(appContext)
                                    val repository = RoomLibraryRepository(appContext, db.libraryDao())
                                    val nextItem = repository.getAdjacentItem(current.uuid, next = true)
                                    if (nextItem != null) {
                                        playItem(appContext, nextItem)
                                    }
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
                            
                            // Build the playback model (back-filling artwork) and the Media3 playlist.
                            val isBound = item.type == com.tortugapower.audiobookplayer.database.entities.ItemType.BOUND
                            val refreshedItem = refreshRemoteUrlsIfNecessary(appContext, item, isBound, processedDir)
                            val playable = if (isBound) {
                                val subItems = getRepository(appContext).getItemsInPathSync(refreshedItem.relativePath ?: "")
                                val books = subItems.filter { it.type == com.tortugapower.audiobookplayer.database.entities.ItemType.BOOK }
                                extractMissingArtwork(books, appContext)
                                books.forEach { ensureChaptersExtracted(it, appContext) }
                                val chaptersBySubBook = books.associate {
                                    it.uuid to getRepository(appContext).getChaptersForBook(it.uuid).first()
                                }
                                PlayableItemBuilder.buildBound(refreshedItem, subItems, chaptersBySubBook)
                            } else {
                                extractMissingArtwork(listOf(refreshedItem), appContext)
                                ensureChaptersExtracted(refreshedItem, appContext)
                                PlayableItemBuilder.buildSingle(refreshedItem, getRepository(appContext).getChaptersForBook(refreshedItem.uuid).first())
                            }
                            _currentPlayable.value = playable
                            _currentTimeline.value = if (isBound) playable.timeline else null

                            val mediaItems = buildMediaItems(playable, processedDir)
                            if (mediaItems.isNotEmpty()) {
                                // For a single BOOK the player offset is just the saved whole-book time.
                                val local = if (isBound) {
                                    playable.timeline.toLocal((refreshedItem.currentTime * 1000).toLong())
                                } else {
                                    BoundTimeline.PlayerPosition(0, (refreshedItem.currentTime * 1000).toLong())
                                }
                                launch(Dispatchers.Main) {
                                    _hasNextItem.value = next
                                    _hasPreviousItem.value = prev
                                    _isTransitioning.value = true

                                    mediaController.setMediaItems(mediaItems, local.mediaItemIndex, local.positionMs)
                                    mediaController.prepare()

                                    // Apply speed and volume
                                    mediaController.setPlaybackSpeed(_playbackSpeed.value)
                                    applyVolume(_volumeBoost.value, _playbackVolume.value)

                                    // Finalize restoration
                                    _currentItem.value = refreshedItem
                                    // Seed the intended whole-book position (positionMs is whole-book);
                                    // prepare() is async so the live player is still 0 here.
                                    _positionMs.value = (refreshedItem.currentTime * 1000).toLong()
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
                PlaybackSettingsManager.getUseChapterContext(appContext).collectLatest { _useChapterContext.value = it }
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
        return repository ?: RoomLibraryRepository(context, AppDatabase.getDatabase(context).libraryDao())
    }

    /** Back-fill embedded artwork for any item missing it (extract -> save -> persist). Runs on IO. */
    private suspend fun extractMissingArtwork(items: List<LibraryItemEntity>, context: Context) {
        val processedDir = File(context.filesDir, "Processed")
        val artworkDir = File(context.filesDir, "Artworks")
        for (entity in items) {
            if (entity.artworkURL != null) continue
            val file = File(processedDir, entity.relativePath ?: "")
            if (!file.exists()) continue
            if (!artworkDir.exists()) artworkDir.mkdirs()
            val artworkFile = File(artworkDir, "${entity.uuid}.jpg")
            if (ArtworkManager.extractAndSaveArtwork(file, artworkFile)) {
                entity.artworkURL = artworkFile.absolutePath
                getRepository(context).updateItem(entity)
            }
        }
    }

    /**
     * Extract & store a single BOOK's embedded chapters on first play if none are stored yet — covers
     * synced books and imports predating chapter extraction (iOS's loadChaptersIfNeeded parity).
     */
    private suspend fun ensureChaptersExtracted(item: LibraryItemEntity, context: Context) {
        if (item.type != com.tortugapower.audiobookplayer.database.entities.ItemType.BOOK) return
        val repo = getRepository(context)
        if (repo.getChaptersForBook(item.uuid).first().isNotEmpty()) return
        val file = File(File(context.filesDir, "Processed"), item.relativePath ?: return)
        if (!file.exists()) return
        val extracted = ChapterExtractionService.extractChapterEntities(file, item.uuid, (item.duration * 1000).toLong())
        val toStore = if (extracted.isNotEmpty()) {
            extracted
        } else {
            // No embedded chapters: persist ONE synthetic chapter spanning the file so we don't re-read
            // and re-parse the whole file (up to the 64 MB moov / 16 MB ID3 scan) on every subsequent
            // play — for a BOUND book that otherwise repeats per sub-book on each play/restore. This
            // mirrors PlayableItemBuilder's synthetic fallback, so the flattened chapter list is identical.
            listOf(
                com.tortugapower.audiobookplayer.database.entities.ChapterEntity(
                    bookUuid = item.uuid,
                    title = item.title,
                    start = 0.0,
                    duration = item.duration,
                    index = 0
                )
            )
        }
        // Idempotent (delete-then-insert in one transaction): the isNotEmpty() guard above is only an
        // optimization to skip re-parsing, not a lock — two concurrent first-play loads of the same
        // synced book can both pass it. The transactional replace means the book ends with exactly one
        // set of chapters, never a doubled list. See PR #20 review.
        repo.replaceChaptersForBook(item.uuid, toStore)
    }

    /**
     * Build the Media3 playlist for a [playable]: one MediaItem per backing file (see
     * [PlayableItem.fileGroups]). A file holding a single chapter (e.g. a BOUND sub-book) shows that
     * chapter's title; a file holding the whole book shows the book title. Artwork falls back to the
     * book's. This is the one place MediaItems are built for both BOUND and single books.
     */
    private fun buildMediaItems(playable: PlayableItem, processedDir: File, headers: Map<String, String>? = null): List<MediaItem> {
        return playable.fileGroups().map { group ->
            val first = group.first()
            val file = first.relativePath?.let { File(processedDir, it) }
            val uri = when {
                file != null && file.exists() -> android.net.Uri.fromFile(file)
                !first.remoteURL.isNullOrEmpty() -> {
                    val baseUri = android.net.Uri.parse(first.remoteURL)
                    headers?.let { registerHeadersForUri(baseUri, it) }
                    baseUri
                }
                else -> android.net.Uri.EMPTY
            }
            val mediaTitle = if (group.size == 1) first.title else playable.title
            val artwork = first.artworkURL ?: playable.artworkURL
            val fallbackAuthor = appContext?.getString(com.tortugapower.audiobookplayer.R.string.library_unknown_author) ?: "Unknown author"
            MediaItem.Builder()
                .setMediaId(first.uuid.ifEmpty { first.relativePath ?: "${playable.uuid}#${first.index}" })
                .setUri(uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(mediaTitle)
                        .setArtist(playable.author ?: fallbackAuthor)
                        .setAlbumTitle(playable.title)
                        .setArtworkUri(artwork?.let {
                            if (it.startsWith("http")) android.net.Uri.parse(it)
                            else android.net.Uri.fromFile(File(it))
                        })
                        .build()
                )
                .build()
        }
    }

    private fun startProgressTracker(context: Context) {
        progressTrackerJob?.cancel()
        progressTrackerJob = scope.launch {
            while (_isPlaying.value) {
                // Tick fast (smooth seek bar) only while something is actually collecting positionMs.
                // PlayerScreen's collectAsStateWithLifecycle unsubscribes when the Activity isn't
                // RESUMED (screen off / backgrounded — even with the player open), so subscriptionCount
                // is 0 then and we fall back to the slow cadence: no 500ms wake-ups with no consumer.
                // The subscriptionCount observer in initialize restarts this loop the moment a collector
                // reappears, so the fast rate resumes promptly.
                val step = PlaybackTickPolicy.tickStepMs(_positionMs.subscriptionCount.value > 0)
                kotlinx.coroutines.delay(step)
                if (!_isPlaying.value) break
                // Emit the live WHOLE-BOOK position for the UI (positionMs is whole-book; invert the
                // session window, which may be per-chapter or whole-book).
                _positionMs.value = currentWholeBookMs()
                // Advance the whole-book chapter index at the same cadence (covers embedded chapters
                // within a file, which fire no MediaItem transition).
                refreshCurrentChapterIndex()
                // Persist progress on a ~10s WALL-CLOCK cadence (field-backed, so loop restarts don't
                // reset it and push persistence back indefinitely).
                val now = System.currentTimeMillis()
                if (PlaybackTickPolicy.shouldPersist(now, lastProgressPersistMs)) {
                    lastProgressPersistMs = now
                    updateProgress(context)
                    StatisticsManager.updateActiveSessionDuration(context)
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

        val timeline = _currentPlayable.value?.timeline
        if (item.type == com.tortugapower.audiobookplayer.database.entities.ItemType.BOUND &&
            (timeline == null || timeline.isEmpty)) {
            return // timeline not built yet (rare, e.g. a persist racing the load); next tick persists
        }
        // Whole-book seconds, inverting whatever window the session presents (book/chapter/single) on the
        // calling (main) thread before any IO hop.
        val currentPos = controllerToWholeBookMs(p.currentMediaItemIndex, p.currentPosition) / 1000.0

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

    fun playItem(context: Context, item: LibraryItemEntity, autoplay: Boolean = true, headers: Map<String, String>? = null) {
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
        // Seed the UI's whole-book position immediately (positionMs is whole-book); the async playable
        // build below re-seeds the same value once the timeline is known.
        _positionMs.value = (item.currentTime * 1000).toLong()

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
            val isBound = item.type == com.tortugapower.audiobookplayer.database.entities.ItemType.BOUND
            // Gather the backing items, back-fill any missing artwork, then build the playback model.
            val (playable, refreshedItem) = withContext(Dispatchers.IO) {
                val refreshedItem = refreshRemoteUrlsIfNecessary(context, item, isBound, processedDir)
                val p = if (isBound) {
                    val subItems = getRepository(context).getItemsInPathSync(refreshedItem.relativePath ?: "")
                    val books = subItems.filter { it.type == com.tortugapower.audiobookplayer.database.entities.ItemType.BOOK }
                    extractMissingArtwork(books, context)
                    books.forEach { ensureChaptersExtracted(it, context) }
                    val chaptersBySubBook = books.associate {
                        it.uuid to getRepository(context).getChaptersForBook(it.uuid).first()
                    }
                    PlayableItemBuilder.buildBound(refreshedItem, subItems, chaptersBySubBook)
                } else {
                    extractMissingArtwork(listOf(refreshedItem), context)
                    ensureChaptersExtracted(refreshedItem, context)
                    PlayableItemBuilder.buildSingle(refreshedItem, getRepository(context).getChaptersForBook(refreshedItem.uuid).first())
                }
                Pair(p, refreshedItem)
            }
            _currentItem.value = refreshedItem
            _currentPlayable.value = playable
            // BOUND books expose a whole-book timeline to the session; single books pass through.
            _currentTimeline.value = if (isBound) playable.timeline else null

            val mediaItems = buildMediaItems(playable, processedDir, headers)
            if (mediaItems.isNotEmpty()) {
                // Resolve the saved whole-book time into the player coordinate (file + offset) it maps to.
                val local = if (isBound) {
                    playable.timeline.toLocal((refreshedItem.currentTime * 1000).toLong())
                } else {
                    BoundTimeline.PlayerPosition(0, (refreshedItem.currentTime * 1000).toLong())
                }
                // Seed the whole-book position for the UI (positionMs is whole-book); the real player is
                // seeded in file coordinates just below.
                _positionMs.value = (item.currentTime * 1000).toLong()
                player?.setMediaItems(mediaItems, local.mediaItemIndex, local.positionMs)
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

    /** Pause playback (no-op if already paused). Used by the sleep timer so it never accidentally resumes. */
    fun pause() {
        player?.pause()
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
        seekWholeBook(chapterAdjustedForwardTarget(currentWholeBookMs(p), _forwardInterval.value * 1000L))
    }

    fun seekBackward() {
        val p = player ?: return
        seekWholeBook(chapterAdjustedRewindTarget(currentWholeBookMs(p), _rewindInterval.value * 1000L))
    }

    /** Whole-book ms target for a rewind, clamped to the current chapter (see [ChapterSkipPolicy]). */
    private fun chapterAdjustedRewindTarget(currentMs: Long, intervalMs: Long): Long {
        val chapter = currentPlayable.value?.chapterAt(currentMs) ?: return currentMs - intervalMs
        return ChapterSkipPolicy.rewindTarget(currentMs, intervalMs, (chapter.start * 1000).toLong())
    }

    /** Whole-book ms target for a forward skip, clamped to the current chapter (see [ChapterSkipPolicy]). */
    private fun chapterAdjustedForwardTarget(currentMs: Long, intervalMs: Long): Long {
        val chapter = currentPlayable.value?.chapterAt(currentMs) ?: return currentMs + intervalMs
        return ChapterSkipPolicy.forwardTarget(currentMs, intervalMs, (chapter.end * 1000).toLong())
    }

    /** Current whole-book position (ms) of the loaded book — for callers like bookmark creation. */
    fun currentWholeBookMs(): Long = player?.let { currentWholeBookMs(it) } ?: 0L

    /** Current playback position in whole-book ms, inverting the virtualized controller window. */
    private fun currentWholeBookMs(p: Player): Long =
        controllerToWholeBookMs(p.currentMediaItemIndex, p.currentPosition)

    /**
     * True when the session controller is CURRENTLY presenting the per-chapter playlist
     * ([com.tortugapower.audiobookplayer.service.BookTimelinePlayer]'s CHAPTER mode). Keyed on the
     * controller's reported window count — a snapshot consistent with the index/position we read in the
     * same breath — NOT on [_useChapterContext], which is set synchronously and can LEAD the controller
     * across the IPC hop on a context toggle. BookTimelinePlayer (the producer) keys off the flag; this
     * consumer must key off what's actually presented, or a toggle mid-tick would invert with the wrong
     * layer and momentarily misread the position (a corrupted persist if it coincided with a save).
     */
    private fun isControllerShowingChapters(playable: PlayableItem): Boolean {
        // >1 chapter so we never ambiguously match a 1-window whole-book/passthrough; when chapters ==
        // files (classic BOUND) both layers agree anyway, so a coincidental match is harmless.
        return playable.chapters.size > 1 && (player?.mediaItemCount ?: 0) == playable.chapters.size
    }

    /**
     * Invert a controller (virtualized session) coordinate to whole-book ms, matching whatever window
     * BookTimelinePlayer is currently presenting:
     *  - CHAPTER playlist: `(chapterIndex, chapter-relative)` via the chapter layer.
     *  - whole-book window / single-book passthrough: `(fileIndex, per-file)` via the file layer (which
     *    is identity at file 0 for the 1-window/single-file cases).
     */
    private fun controllerToWholeBookMs(mediaItemIndex: Int, positionMs: Long): Long {
        val playable = _currentPlayable.value
        val timeline = playable?.timeline
        return when {
            timeline == null || timeline.isEmpty -> positionMs
            isControllerShowingChapters(playable) -> timeline.wholeBookOfChapter(mediaItemIndex, positionMs)
            else -> timeline.toAbsoluteMs(mediaItemIndex, positionMs)
        }
    }

    /**
     * Seek to an absolute whole-book position (ms) via the app's controller, in whichever coordinate
     * space the session currently exposes:
     *  - BOUND + book context: the session is a single whole-book window, so seek the whole-book ms
     *    directly (BookTimelinePlayer maps it back to the right sub-book).
     *  - BOUND + chapter context: the session passes the per-file playlist through, so map to
     *    (sub-book index, per-item offset) here.
     *  - single BOOK: position is already whole-book.
     * The service's media-button handler seeks the real ExoPlayer directly via
     * [seekRelativeAcrossChapters] instead (it bypasses the session).
     */
    fun seekWholeBook(wholeBookMs: Long) {
        val p = player ?: return
        val playable = _currentPlayable.value
        val timeline = playable?.timeline
        when {
            timeline == null || timeline.isEmpty -> p.seekTo(wholeBookMs.coerceAtLeast(0L))
            isControllerShowingChapters(playable) -> {
                // CHAPTER window: seek by (chapter index, offset within the chapter).
                val cp = timeline.chapterLocalOf(wholeBookMs.coerceIn(0L, timeline.totalDurationMs))
                p.seekTo(cp.chapterIndex, cp.positionMs)
            }
            // WHOLE_BOOK window (BOUND book context) or single-book passthrough: the window is whole-book.
            else -> p.seekTo(wholeBookMs.coerceIn(0L, timeline.totalDurationMs))
        }
    }

    /**
     * Seek the REAL ExoPlayer by [deltaMs] (negative = backward), crossing sub-book boundaries on the
     * whole-book timeline. Used by [AudioPlayerService]'s media-button / Bluetooth handlers, which hold
     * the real player and operate in per-file coordinates (they bypass the virtualizing session). The
     * in-app controls go through [seekForward]/[seekBackward]/[seekWholeBook] instead.
     */
    fun seekRelativeAcrossChapters(player: Player, deltaMs: Long) {
        // Operates on the REAL (file) playlist, so the file layer of the whole-book timeline applies to
        // both BOUND and single books (single = one file span, identity). Sourced from _currentPlayable
        // like the rest of the file (not _currentTimeline).
        val timeline = _currentPlayable.value?.timeline
        val currentAbs = if (timeline != null && !timeline.isEmpty) {
            timeline.toAbsoluteMs(player.currentMediaItemIndex, player.currentPosition)
        } else {
            player.currentPosition
        }
        // Clamp to the current chapter (same rule as the in-app buttons) before seeking.
        val chapter = currentPlayable.value?.chapterAt(currentAbs)
        val targetAbs = when {
            chapter == null -> currentAbs + deltaMs
            deltaMs < 0 -> ChapterSkipPolicy.rewindTarget(currentAbs, -deltaMs, (chapter.start * 1000).toLong())
            else -> ChapterSkipPolicy.forwardTarget(currentAbs, deltaMs, (chapter.end * 1000).toLong())
        }
        if (timeline != null && !timeline.isEmpty) {
            val local = timeline.toLocal(targetAbs.coerceIn(0L, timeline.totalDurationMs))
            player.seekTo(local.mediaItemIndex, local.positionMs)
        } else {
            player.seekTo(targetAbs.coerceAtLeast(0L))
        }
    }

    /** Seek to an absolute whole-book position. Alias kept for existing callers; see [seekWholeBook]. */
    fun seekTo(positionMs: Long) {
        seekWholeBook(positionMs)
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

    private suspend fun refreshRemoteUrlsIfNecessary(context: Context, item: LibraryItemEntity, isBound: Boolean, processedDir: File): LibraryItemEntity {
        val repo = getRepository(context)
        val isLocal = if (isBound) {
            val subItems = repo.getItemsInPathSync(item.relativePath ?: "")
            subItems.all { sub ->
                val file = sub.relativePath?.let { File(processedDir, it) }
                file != null && file.exists()
            }
        } else {
            val file = item.relativePath?.let { File(processedDir, it) }
            file != null && file.exists()
        }

        if (!isLocal) {
            // First resolve external server stream URLs (Jellyfin/Audiobookshelf)
            val resolvedItem = repo.resolveStreamingUrl(item)
            
            try {
                if (isBound) {
                    val response = NetworkClient.libraryApi.getContents(resolvedItem.relativePath ?: "")
                    if (response.isSuccessful && response.body() != null) {
                        val body = response.body()!!
                        val subItems = repo.getItemsInPathSync(resolvedItem.relativePath ?: "")
                        val resolvedSubItems = repo.resolveStreamingUrls(subItems)
                        body.content.forEach { remoteSub ->
                            val localSub = resolvedSubItems.find { it.uuid == remoteSub.uuid || it.relativePath == remoteSub.relativePath }
                            if (localSub != null && !remoteSub.remoteURL.isNullOrEmpty()) {
                                localSub.remoteURL = remoteSub.remoteURL
                                if (!remoteSub.artworkURL.isNullOrEmpty()) {
                                    localSub.artworkURL = remoteSub.artworkURL
                                }
                                repo.updateItem(localSub)
                            }
                        }
                        android.util.Log.d("PlaybackManager", "✅ Refreshed remote URLs for bound item sub-books")
                    } else {
                        // Fallback: save resolved sub-book URLs to DB
                        val subItems = repo.getItemsInPathSync(resolvedItem.relativePath ?: "")
                        val resolvedSubItems = repo.resolveStreamingUrls(subItems)
                        resolvedSubItems.forEach { repo.updateItem(it) }
                    }
                } else if (!resolvedItem.remoteURL.isNullOrEmpty()) {
                    val hasExternalResource = resolvedItem.externalResources.any { it.syncStatus == "stream" || it.syncStatus == "downloaded" }
                    if (!hasExternalResource) {
                        // Only query Bookplayer API signed URLs if it's not a Jellyfin/Audiobookshelf item
                        val response = NetworkClient.libraryApi.getRemoteFileURL(
                            path = resolvedItem.relativePath ?: "",
                            uuid = resolvedItem.uuid
                        )
                        if (response.isSuccessful && response.body() != null) {
                            val body = response.body()!!
                            val remoteItem = body.content.firstOrNull { it.uuid == resolvedItem.uuid || it.relativePath == resolvedItem.relativePath }
                            if (remoteItem != null && !remoteItem.remoteURL.isNullOrEmpty()) {
                                resolvedItem.remoteURL = remoteItem.remoteURL
                                if (!remoteItem.artworkURL.isNullOrEmpty()) {
                                    resolvedItem.artworkURL = remoteItem.artworkURL
                                }
                                repo.updateItem(resolvedItem)
                                android.util.Log.d("PlaybackManager", "✅ Refreshed remote URL for single item: ${resolvedItem.title}")
                            }
                        }
                    } else {
                        repo.updateItem(resolvedItem)
                    }
                }
            } catch (e: Exception) {
                // Fallback: update DB with resolved Jellyfin/ABS streaming URLs even if cloud fetch fails
                repo.updateItem(resolvedItem)
                android.util.Log.e("PlaybackManager", "❌ Failed to refresh remote URL(s): ${e.message}")
            }
        }
        return repo.getItemById(item.uuid) ?: item
    }

    fun release() {
        controllerFuture?.let {
            MediaController.releaseFuture(it)
        }
        player = null
        controllerFuture = null
    }
}
