package com.tortugapower.audiobookplayer.logic

import android.content.ComponentName
import kotlinx.coroutines.flow.combine
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.BookmarkEntity
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.network.NetworkClient
import com.tortugapower.audiobookplayer.repository.LibraryRepository
import com.tortugapower.audiobookplayer.repository.RoomLibraryRepository
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
    // Speed steps the Android Auto speed button cycles through (a picker isn't possible in the car).
    // Chosen to line up with Media3's built-in ICON_PLAYBACK_SPEED_* glyphs so the button shows the speed.
    private val SPEED_PRESETS = listOf(0.8f, 1.0f, 1.2f, 1.5f, 1.8f, 2.0f)

    // Upper bound on the on-play contents fetch for an offloaded bound book, so a slow server can't
    // hang playback (fail-soft: fall through to whatever's local).
    private const val CONTENTS_FETCH_TIMEOUT_MS = 15_000L

    // Cap the per-process "already attempted remote chapter fetch" dedup set (cleared on overflow).
    private const val REMOTE_ATTEMPT_CAP = 1000

    val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var controllerFuture: ListenableFuture<MediaController>? = null
    var player: Player? = null
        private set

    // Completed once the last-played-item restore has populated the playlist (or determined
    // there's nothing to restore). awaitPlayer gates on it so a widget tap that cold-starts the
    // process neither no-ops on an empty playlist nor gets its playItem clobbered by a
    // late-finishing restore.
    private val restoreSettled = kotlinx.coroutines.CompletableDeferred<Unit>()

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

    // Per-target values injected at [initialize] so this can move to :core without referencing :app's
    // service/widget/R. Defaults keep headless/test contexts working.
    private var unknownAuthorLabel: String = "Unknown author"

    // --- Observable playback state, the app-scoped source of truth. Collect from Compose via
    // collectAsStateWithLifecycle; read `.value` from non-Compose code. Only PlaybackManager writes it. ---

    private val _currentItem = MutableStateFlow<LibraryItemEntity?>(null)
    val currentItem: StateFlow<LibraryItemEntity?> = _currentItem.asStateFlow()

    private val _hasNextItem = MutableStateFlow(false)
    val hasNextItem: StateFlow<Boolean> = _hasNextItem.asStateFlow()
    private val _hasPreviousItem = MutableStateFlow(false)
    val hasPreviousItem: StateFlow<Boolean> = _hasPreviousItem.asStateFlow()

    // ACTUAL playback (Media3 onIsPlayingChanged = READY + playWhenReady + not suppressed). Drives the
    // internal side-effects (progress tracker, smart-rewind, statistics, pause time) — deliberately NOT the
    // public button state, which must reflect intent (below), not whether audio is literally advancing.
    private val _isPlaying = MutableStateFlow(false)

    private val _playbackState = MutableStateFlow(Player.STATE_IDLE)
    val playbackState: StateFlow<Int> = _playbackState.asStateFlow()

    // The user's INTENT to play (mirrors iOS PlayerManager.isPlaying), so the play/pause button shows
    // "playing" from the moment play is tapped — through the async load/URL-fetch window (playbackQueuedFlag)
    // and through buffering (playWhenReadyFlag while READY/BUFFERING) — and only reads "paused" when truly
    // paused/idle/ended. Media3's raw isPlaying is false during buffering, which wrongly flipped the button
    // back to "play" mid-stream. [isPlayingIntent] is the pure rule; [recomputeIsPlaying] re-applies it when
    // an input changes. Plain flags + a MutableStateFlow (not combine+stateIn) so the object stays free of an
    // eager Dispatchers.Main coroutine at init — keeping PlaybackManager usable from JVM unit tests.
    private var playWhenReadyFlag = false
    private var playbackQueuedFlag = false
    private val _isPlayingIntent = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlayingIntent.asStateFlow()

    private fun recomputeIsPlaying() {
        _isPlayingIntent.value = isPlayingIntent(playbackQueuedFlag, playWhenReadyFlag, _playbackState.value)
    }

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
    // System media-stream (device) volume as a 0..1 fraction, for the watch's crown volume indicator. Only
    // meaningful when device-volume control is enabled (the watch); stays 0 on the phone.
    private val _deviceVolume = MutableStateFlow(0f)
    val deviceVolume: StateFlow<Float> = _deviceVolume.asStateFlow()

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

    /**
     * @param sessionService the target's media session service (phone: `AudioPlayerService`; watch: its own)
     *   — the [MediaController] connects to it. Injected so this file carries no `:app` service reference.
     * @param unknownAuthorLabel localized "unknown author" fallback (phone reads it from its `R.string`).
     * @param onPlaybackStateChanged called on each item/play-state change with whether the item changed —
     *   the phone uses it to update its home-screen widget; no-op on targets without one.
     */
    fun initialize(
        context: Context,
        libraryRepository: LibraryRepository,
        sessionService: ComponentName,
        unknownAuthorLabel: String = "Unknown author",
        onPlaybackStateChanged: (itemChanged: Boolean, isPlaying: Boolean) -> Unit = { _, _ -> },
    ) {
        if (player != null) return
        repository = libraryRepository
        val appContext = context.applicationContext
        this.appContext = appContext
        this.unknownAuthorLabel = unknownAuthorLabel

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
            // Notify the target of item/play-state changes (the phone patches its home-screen widget;
            // watch/tests no-op). `itemChanged` distinguishes a book change (rare, full rebuild) from a
            // play/pause flip (cheap in-place patch).
            var lastItemUuid: String? = null
            var seenFirstEmission = false
            combine(_currentItem, isPlaying) { item, playing -> Pair(item, playing) }
                .collect { (item, playing) ->
                    val itemChanged = !seenFirstEmission || item?.uuid != lastItemUuid
                    seenFirstEmission = true
                    lastItemUuid = item?.uuid
                    onPlaybackStateChanged(itemChanged, playing)
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

        val sessionToken = SessionToken(appContext, sessionService)
        controllerFuture = MediaController.Builder(appContext, sessionToken).buildAsync()
        controllerFuture?.addListener({
            try {
                val mediaController = controllerFuture?.get() ?: return@addListener
                player = mediaController
                // Seed the device-volume fraction (0 unless device-volume control is enabled, i.e. the watch).
                _deviceVolume.value = deviceVolumeFraction(mediaController)

                // Add listener once
                mediaController.addListener(object : Player.Listener {
                    override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) {
                        _deviceVolume.value = deviceVolumeFraction(mediaController)
                    }

                    override fun onIsPlayingChanged(playing: Boolean) {
                        if (_isPlaying.value == playing) return
                        _isPlaying.value = playing
                        if (!playing) {
                            lastPauseTime = System.currentTimeMillis()
                            updateProgress(appContext)
                        } else {
                            // Real playback has started — the load/buffering "queued" window is over.
                            playbackQueuedFlag = false
                            recomputeIsPlaying()
                            if (smartRewindEnabled) {
                                applySmartRewind()
                            }
                            startProgressTracker(appContext)
                        }
                        StatisticsManager.setPlaybackState(appContext, _currentItem.value, playing)
                    }

                    // Track the user's play/pause INTENT (not actual playback) so the button reflects it
                    // through buffering. An explicit pause (playWhenReady=false) also cancels a queued play.
                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                        playWhenReadyFlag = playWhenReady
                        if (!playWhenReady) playbackQueuedFlag = false
                        recomputeIsPlaying()
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
                        // Playback bottomed out (natural end, stop, OR a stream that errored during
                        // buffering → IDLE). Clear the queued intent so it can't strand the button on
                        // "playing": onIsPlayingChanged(true) never fired, so nothing else would clear it.
                        if (state == Player.STATE_IDLE || state == Player.STATE_ENDED) {
                            playbackQueuedFlag = false
                        }
                        recomputeIsPlaying()
                        if (state == Player.STATE_ENDED) {
                            updateProgress(appContext, forceFinished = true)
                            StatisticsManager.setPlaybackState(appContext, _currentItem.value, false)
                            if (SleepTimerManager.isEndOfChapter.value) {
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
                    try {
                        restoreLastPlayedItem(appContext, mediaController)
                    } finally {
                        // Settle even when there was nothing to restore, so awaitPlayer callers
                        // (widget taps on a cold-started process) stop waiting.
                        restoreSettled.complete(Unit)
                    }
                }

                // Apply current speed and volume when player is ready
                mediaController.setPlaybackSpeed(_playbackSpeed.value)
                applyVolume(_volumeBoost.value, _playbackVolume.value)
            } catch (e: Exception) {
                restoreSettled.complete(Unit)
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

    // Files we've already attempted a remote chapter fetch for this process, so we don't re-download the
    // moov on every play of a not-downloaded book. Not persisted (a failed/empty remote fetch must NOT
    // block real extraction once the file is downloaded, unlike the local synthetic-chapter marker).
    private val remoteChapterAttempts = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Best-effort audio file extension for picking the chapter parser, from most to least reliable:
     * the item's `relativePath`, then its `originalFileName` (set for external items whose relativePath
     * is null, e.g. AudiobookShelf), then the remote URL's last path segment with any query/fragment
     * stripped. A streaming URL like `Items/<id>/Download?api_key=...` yields no extension → we fall
     * through rather than mis-detecting. Pure (no Android APIs) so it's unit-tested. Lowercased, no dot.
     */
    internal fun audioExtensionFor(item: LibraryItemEntity, url: String): String {
        val fromUrl = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        for (candidate in listOfNotNull(item.relativePath, item.originalFileName, fromUrl)) {
            val ext = candidate.substringAfterLast('.', "")
            if (ext.length in 1..5 && ext.all { it.isLetterOrDigit() }) return ext.lowercase()
        }
        return ""
    }

    /**
     * Background (non-blocking) embedded-chapter extraction for OFFLOADED/streamed files: range-fetch the
     * metadata region over HTTP and persist the chapters (iOS `loadChaptersIfNeeded` on a streamed asset).
     * A single book then refreshes its timeline live; a bound book persists only (shows next reload —
     * matching iOS, which doesn't rebuild the active bound timeline mid-session). At most once per file
     * per process; downloaded files are left to the local [ensureChaptersExtracted] path.
     */
    private fun extractRemoteChaptersInBackground(context: Context, item: LibraryItemEntity, isBound: Boolean) {
        scope.launch(Dispatchers.IO) {
            try {
                val processedDir = File(context.filesDir, "Processed")
                val repo = getRepository(context)
                val targets = if (isBound) {
                    repo.getItemsInPathSync(item.relativePath ?: "")
                        .filter { it.type == com.tortugapower.audiobookplayer.database.entities.ItemType.BOOK }
                } else {
                    listOf(item)
                }
                var extractedAny = false
                for (sub in targets) {
                    val rp = sub.relativePath
                    if (rp != null && File(processedDir, rp).exists()) continue           // downloaded → local path handles it
                    if (repo.getChaptersForBook(sub.uuid).first().isNotEmpty()) continue  // already have chapters
                    // Bound the per-process dedup set (clear on overflow — a re-attempt is harmless).
                    if (remoteChapterAttempts.size >= REMOTE_ATTEMPT_CAP) remoteChapterAttempts.clear()
                    if (!remoteChapterAttempts.add(sub.uuid)) continue                    // attempted this session
                    val resolved = repo.resolveStreamingUrl(sub)
                    val url = resolved.remoteURL?.takeIf { it.isNotEmpty() } ?: continue
                    val ext = audioExtensionFor(sub, url)
                    val headers = getHeadersForUri(android.net.Uri.parse(url))
                    val chapters = ChapterExtractionService.extractChapterEntitiesRemote(
                        url, headers, ext, sub.uuid, (sub.duration * 1000).toLong()
                    )
                    // Only persist a genuinely multi-chapter result (a single span adds nothing over the
                    // synthetic fallback, and persisting it would wrongly block real extraction after download).
                    if (chapters.size > 1) {
                        repo.replaceChaptersForBook(sub.uuid, chapters)
                        extractedAny = true
                    }
                }
                // Single book: swap in the richer chapter list live (bound = persist-only per iOS).
                if (extractedAny && !isBound && _currentItem.value?.uuid == item.uuid) {
                    rebuildCurrentPlayableChapters(context, item.uuid)
                }
            } catch (e: Exception) {
                android.util.Log.e("PlaybackManager", "Remote chapter extraction failed: ${e.message}")
            }
        }
    }

    /**
     * Rebuild the current SINGLE book's in-memory playable from freshly-stored chapters (no player
     * reload — one file, chapters are virtual), so the chapter list / notification title / `<>` nav
     * update live mid-playback. No-op if the current item changed while the remote fetch ran.
     */
    private suspend fun rebuildCurrentPlayableChapters(context: Context, uuid: String) {
        if (_currentItem.value?.uuid != uuid) return
        val fresh = getRepository(context).getItemById(uuid) ?: return
        if (fresh.type == com.tortugapower.audiobookplayer.database.entities.ItemType.BOUND) return
        val chapters = getRepository(context).getChaptersForBook(uuid).first()
        val playable = PlayableItemBuilder.buildSingle(fresh, chapters)
        withContext(Dispatchers.Main) {
            if (_currentItem.value?.uuid != uuid) return@withContext
            _currentPlayable.value = playable
            refreshCurrentChapterIndex()
        }
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
            val fallbackAuthor = unknownAuthorLabel
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
        // Queue the play INTENT up front so the button shows "playing" during the async load / URL-fetch
        // window below (before the player is even prepared); cleared once real playback starts or is paused.
        if (autoplay) {
            playbackQueuedFlag = true
            recomputeIsPlaying()
        }
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
            val (playable, refreshedItem) = buildPlayableModel(context, item, isBound, processedDir)
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
            } else {
                // Nothing playable resolved (e.g. no backing files / all URIs empty): playback will never
                // start, so no state transition would clear the queued intent. Clear it here so the button
                // doesn't strand on "playing".
                playbackQueuedFlag = false
                recomputeIsPlaying()
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
            } else {
                // e.g. a stale Android Auto browse row for a deleted/moved item — don't silently vanish.
                android.util.Log.w("PlaybackManager", "playItemByPath: no library item for path '$path'")
            }
        }
    }

    /** Builds the in-memory playback model (chapters, artwork back-fill, timeline) for [item]. Off the
     *  main thread. Shared by [playItem] and the Android Auto browse resolver. */
    private suspend fun buildPlayableModel(
        context: Context,
        item: LibraryItemEntity,
        isBound: Boolean,
        processedDir: File
    ): Pair<PlayableItem, LibraryItemEntity> = withContext(Dispatchers.IO) {
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
        // Fire-and-forget: for offloaded/streamed files, range-fetch embedded chapters in the background
        // (doesn't block play start). Single book refreshes its timeline live; bound persists for reload.
        extractRemoteChaptersInBackground(context, refreshedItem, isBound)
        Pair(p, refreshedItem)
    }

    /** File media items + start position, for an external session (Android Auto) to set on the player. */
    data class SessionMediaItems(val items: List<MediaItem>, val startIndex: Int, val startPositionMs: Long)

    /**
     * Android Auto browse-play: load [path], set up the playback MODEL + player settings (currentItem/
     * playable/timeline for [com.tortugapower.audiobookplayer.service.BookTimelinePlayer] virtualization,
     * speed/volume), and RETURN the file media items + start position for the SESSION to set on its player
     * itself. We must NOT set the player here (the session does that from the return value): pushing an
     * empty/non-idle playlist would crash `SimpleBasePlayer.getState`. Returns null if [path] can't resolve.
     */
    suspend fun resolveSessionMediaItems(context: Context, path: String): SessionMediaItems? {
        val item = getRepository(context).getItemByPath(path) ?: return null
        if (_currentItem.value?.uuid != item.uuid) updateProgress(context, itemToUpdate = _currentItem.value)
        if (item.isFinished) {
            item.currentTime = 0.0; item.isFinished = false; item.percentCompleted = 0.0
            getRepository(context).updateItemProgress(item.uuid, 0.0, false)
        }
        val processedDir = File(context.filesDir, "Processed")
        val isBound = item.type == com.tortugapower.audiobookplayer.database.entities.ItemType.BOUND
        val (playable, refreshedItem) = buildPlayableModel(context, item, isBound, processedDir)
        val mediaItems = buildMediaItems(playable, processedDir, null)
        if (mediaItems.isEmpty()) return null

        _currentItem.value = refreshedItem
        _currentPlayable.value = playable
        _currentTimeline.value = if (isBound) playable.timeline else null
        val local = if (isBound) playable.timeline.toLocal((refreshedItem.currentTime * 1000).toLong())
                    else BoundTimeline.PlayerPosition(0, (refreshedItem.currentTime * 1000).toLong())
        _positionMs.value = (refreshedItem.currentTime * 1000).toLong()
        PlaybackSettingsManager.setLastItemUuid(context, item.uuid)
        player?.setPlaybackSpeed(_playbackSpeed.value)
        applyVolume(_volumeBoost.value, _playbackVolume.value)
        return SessionMediaItems(mediaItems, local.mediaItemIndex, local.positionMs)
    }

    /** Pause playback (no-op if already paused). Used by the sleep timer so it never accidentally resumes. */
    fun pause() {
        player?.pause()
    }

    /**
     * Rebuilds the last-played book into the controller (playlist, saved position, speed/volume).
     * Runs on IO; the final player mutation hops to Main and is AWAITED, so when this returns the
     * playlist is actually populated — [awaitPlayer] relies on that via [restoreSettled].
     */
    private suspend fun restoreLastPlayedItem(appContext: Context, mediaController: MediaController) {
        val lastUuid = PlaybackSettingsManager.getLastItemUuid(appContext).first() ?: return
        val item = getRepository(appContext).getItemById(lastUuid) ?: return
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
            withContext(Dispatchers.Main) {
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
            // Offloaded book restored on cold start: fetch embedded chapters in the background too.
            extractRemoteChaptersInBackground(appContext, refreshedItem, isBound)
        }
    }

    /**
     * Persists progress, then stops playback and unloads the current item. Must be called on the
     * main thread. Used when the loaded book's backing file is about to be deleted (Storage
     * Management), so ExoPlayer releases the file instead of stalling mid-playback.
     */
    fun stopAndUnloadCurrentItem(context: Context) {
        updateProgress(context, itemToUpdate = _currentItem.value)
        player?.stop()
        player?.clearMediaItems()
        _isPlaying.value = false
        playWhenReadyFlag = false
        playbackQueuedFlag = false
        recomputeIsPlaying()
        _currentItem.value = null
        _currentPlayable.value = null
        _currentTimeline.value = null
        _positionMs.value = 0L
        _showPlayerScreen.value = false
        scope.launch {
            PlaybackSettingsManager.setLastItemUuid(context, null)
        }
    }

    /**
     * Suspends until the MediaController has connected AND the last-played-item restore has
     * settled, or [timeoutMs] elapses. Entry points that can arrive before initialization
     * completes (e.g. a widget tap cold-starting the process) must await this instead of hitting
     * the `player ?: return` no-op guards — a bound-but-unpopulated player would make play/pause
     * and seeks silently no-op, and a playItem could be overwritten by the in-flight restore.
     */
    suspend fun awaitPlayer(timeoutMs: Long = 5_000): Player? {
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            while (player == null) kotlinx.coroutines.delay(50)
            restoreSettled.await()
            player
        }
    }

    fun togglePlayPause() {
        val p = player ?: return
        // Toggle on the SAME intent the button displays (isPlaying = queued || playWhenReady while
        // READY/BUFFERING), not Media3's raw isPlaying: while buffering or during the queued-load window the
        // button shows "playing", so a tap must pause rather than call play() again.
        if (isPlaying.value) {
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
     * whole-book timeline. Used by the media session service's media-button / Bluetooth handlers, which hold
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

    // --- Android Auto Now Playing helpers ---

    /**
     * Step the playback speed to the next preset (wrapping), since Android Auto can't present a speed
     * picker. Persists it (the settings collector applies it to the player). Returns the new speed.
     */
    fun cyclePlaybackSpeed(context: Context): Float {
        val next = nextSpeedPreset(_playbackSpeed.value)
        setPlaybackSpeed(context, next)
        return next
    }

    /** First speed preset strictly greater than [current], wrapping to the first. Pure (testable). */
    internal fun nextSpeedPreset(current: Float): Float =
        SPEED_PRESETS.firstOrNull { it > current + 0.001f } ?: SPEED_PRESETS.first()

    /**
     * The play/pause button's INTENT state (pure, testable) — the Android analogue of iOS
     * `PlayerManager.isPlaying`. True when a play is [queued] (tapped play, still loading/fetching the URL
     * before the player is prepared) OR the player wants to play ([playWhenReady]) while it is READY or
     * BUFFERING. Buffering therefore reads as "playing" (the user tapped play); only a genuine pause
     * (playWhenReady=false) or a non-playing state (IDLE/ENDED, e.g. after an error or book end) reads as
     * "paused".
     */
    internal fun isPlayingIntent(queued: Boolean, playWhenReady: Boolean, state: Int): Boolean =
        queued || (playWhenReady && (state == Player.STATE_READY || state == Player.STATE_BUFFERING))

    /** Outcome of a car-initiated bookmark, so the caller can surface feedback. */
    sealed interface BookmarkOutcome {
        data class Created(val timeSeconds: Double) : BookmarkOutcome
        data class Existed(val timeSeconds: Double) : BookmarkOutcome
        data object Failed : BookmarkOutcome
    }

    /**
     * Create a bookmark at the current whole-book position (dedup against an existing one), mirroring
     * the in-app [PlayerViewModel.addBookmark]. Whole-book seconds so it round-trips like the in-app
     * bookmarks; sync scheduling is handled by the repository.
     */
    suspend fun createBookmarkAtCurrentPosition(context: Context): BookmarkOutcome {
        val item = _currentItem.value ?: return BookmarkOutcome.Failed
        if (player == null) return BookmarkOutcome.Failed
        val timeSeconds = currentWholeBookMs() / 1000.0
        val repository = getRepository(context)
        val existing = repository.getBookmarkAtTime(item.uuid, timeSeconds)
        if (existing != null) return BookmarkOutcome.Existed(existing.time)
        repository.addBookmark(BookmarkEntity(bookUuid = item.uuid, time = timeSeconds))
        return BookmarkOutcome.Created(timeSeconds)
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

    /**
     * Crown volume (watch standalone): nudge the system media-stream (device) volume one step through the
     * session player. No-op when device-volume control isn't enabled (the phone, see
     * [com.tortugapower.audiobookplayer.service.MediaPlaybackService.deviceVolumeControlEnabled]) or before
     * the controller connects, so the call is safe from any target. Main-thread only, like the other
     * transport calls.
     */
    fun increaseDeviceVolume() = adjustDeviceVolume(up = true)
    fun decreaseDeviceVolume() = adjustDeviceVolume(up = false)

    private fun adjustDeviceVolume(up: Boolean) {
        val p = player ?: return
        if (!p.isCommandAvailable(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)) return
        // No FLAG_SHOW_UI: on Wear that pops a full-screen system slider that grabs the crown. We adjust
        // silently and render our own peripheral volume indicator ([deviceVolume]) on the now-playing screen.
        if (up) p.increaseDeviceVolume(0) else p.decreaseDeviceVolume(0)
    }

    /** Current device (media-stream) volume as a 0..1 fraction, or 0 when the range is unknown/unsupported. */
    private fun deviceVolumeFraction(p: Player): Float =
        deviceVolumeFraction(p.deviceVolume, p.deviceInfo.minVolume, p.deviceInfo.maxVolume)

    /** Pure 0..1 mapping of [volume] within [[minVolume], [maxVolume]] (0 when the range is empty). Unit-tested. */
    fun deviceVolumeFraction(volume: Int, minVolume: Int, maxVolume: Int): Float {
        val range = maxVolume - minVolume
        return if (range > 0) ((volume - minVolume).toFloat() / range).coerceIn(0f, 1f) else 0f
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
            // Empty sub-items on a bound book = offloaded/never-fetched contents → treat as NOT local so
            // we fetch the sub-item list below (List.all is vacuously true on an empty list, which would
            // otherwise skip the fetch and hand buildBound an empty timeline). iOS does the same via
            // PlayerLoaderService awaiting syncListContents when getMaxItemsCount == 0.
            subItems.isNotEmpty() && subItems.all { sub ->
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
                    // Bounded so a slow/unreachable server can't hang playback (OkHttp also has timeouts).
                    val response = kotlinx.coroutines.withTimeoutOrNull(CONTENTS_FETCH_TIMEOUT_MS) {
                        NetworkClient.libraryApi.getContents(resolvedItem.relativePath ?: "")
                    }
                    if (response != null && response.isSuccessful && response.body() != null) {
                        val body = response.body()!!
                        val subItems = repo.getItemsInPathSync(resolvedItem.relativePath ?: "")
                        val resolvedSubItems = repo.resolveStreamingUrls(subItems)
                        // Offloaded bound book whose sub-items were never fetched: insert the missing ones
                        // (subscribed accounts only) so buildBound has a timeline to build. Reuses the same
                        // upsert as the background contents-sync task.
                        val syncActive = repo.isCloudSyncActive()
                        val dao = if (syncActive) AppDatabase.getDatabase(context).libraryDao() else null
                        val generatedUuids = mutableSetOf<String>()
                        body.content.forEach { remoteSub ->
                            val localSub = resolvedSubItems.find { it.uuid == remoteSub.uuid || it.relativePath == remoteSub.relativePath }
                            if (localSub != null) {
                                if (!remoteSub.remoteURL.isNullOrEmpty()) {
                                    localSub.remoteURL = remoteSub.remoteURL
                                    if (!remoteSub.artworkURL.isNullOrEmpty()) {
                                        localSub.artworkURL = remoteSub.artworkURL
                                    }
                                    repo.updateItem(localSub)
                                }
                            } else if (dao != null) {
                                LibraryContentsSync.upsertItem(dao, null, remoteSub, generatedUuids)
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
