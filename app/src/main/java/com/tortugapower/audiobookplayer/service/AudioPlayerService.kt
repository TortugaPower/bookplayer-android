package com.tortugapower.audiobookplayer.service

import android.app.PendingIntent
import android.content.Intent
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.media.utils.MediaConstants
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.tortugapower.audiobookplayer.MainActivity
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.logic.ArtworkManager
import com.tortugapower.audiobookplayer.logic.PlaybackManager
import com.tortugapower.audiobookplayer.logic.PlaybackSettingsManager
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CacheBitmapLoader

class AudioPlayerService : MediaLibraryService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaLibrarySession? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(false)
        
        val customHttpDataSourceFactory = object : HttpDataSource.Factory {
            override fun createDataSource(): HttpDataSource {
                val delegate = httpDataSourceFactory.createDataSource()
                return object : HttpDataSource by delegate {
                    override fun open(dataSpec: DataSpec): Long {
                        // Attach auth only when the request host belongs to a configured external
                        // server (resolved by authority in PlaybackManager), and reset any properties
                        // from a previous open so headers never bleed onto another host's request.
                        delegate.clearAllRequestProperties()
                        PlaybackManager.getHeadersForUri(dataSpec.uri)?.forEach { (k, v) ->
                            delegate.setRequestProperty(k, v)
                        }
                        return delegate.open(dataSpec)
                    }
                }
            }

            override fun setDefaultRequestProperties(defaultRequestProperties: MutableMap<String, String>): HttpDataSource.Factory {
                httpDataSourceFactory.setDefaultRequestProperties(defaultRequestProperties)
                return this
            }
        }

        // DefaultDataSource handles file://, asset://, etc. automatically
        val dataSourceFactory = DefaultDataSource.Factory(this, customHttpDataSourceFactory)

        player = ExoPlayer.Builder(this)
            // handleAudioFocus = true: ExoPlayer requests/holds audio focus while playing. Required for
            // Android Auto — the car only routes audio to its speakers for the app that holds media
            // focus, so without it playback advances but is silent in the car (progress moves, no sound).
            // Also gives correct ducking/pause on calls & nav prompts, matching iOS's AVAudioSession.
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            // Safe default before the first item resolves; narrowed per-item below (a Wi-Fi lock is
            // only needed while actually streaming). Requires only the WAKE_LOCK permission; ExoPlayer
            // acquires/releases the wake lock (and Wi-Fi lock, in NETWORK mode) with the play state.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory))
            .build()

        player?.let { p ->
            // Narrow the wake mode per item: hold the Wi-Fi lock only while a chapter actually streams
            // from a remote URL; local files need just the CPU lock (saves battery for the common
            // local-playback case). Re-evaluated on each transition, so BOUND books with mixed
            // local/remote chapters get the right mode per chapter.
            p.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    p.setWakeMode(wakeModeFor(mediaItem))
                }

                // Surface a 401/403 on an external-server stream as an app-level error (the stored
                // session died mid-playback). Detected here on the REAL player — the full cause
                // chain doesn't survive the session-controller bundling that PlaybackManager sees.
                override fun onPlayerError(error: PlaybackException) {
                    var cause: Throwable? = error
                    while (cause != null) {
                        if (cause is HttpDataSource.InvalidResponseCodeException &&
                            (cause.responseCode == 401 || cause.responseCode == 403)
                        ) {
                            val uri = p.currentMediaItem?.localConfiguration?.uri
                            if (uri != null && PlaybackManager.hasHeadersForUri(uri)) {
                                PlaybackManager.reportExternalStreamAuthError()
                            }
                            return
                        }
                        cause = cause.cause
                    }
                }
            })

            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("OPEN_PLAYER", true)
            }
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            // Wrap the ExoPlayer so the session (and thus the OS notification scrubber) reports a
            // whole-book timeline for BOUND books in book context, matching the in-app player. The
            // real ExoPlayer `p` keeps its per-file playlist (gapless auto-advance); the service still
            // drives `p` directly for media-button seeks and the LoudnessEnhancer / wake mode.
            val sessionPlayer = BookTimelinePlayer(
                wrapped = p,
                timelineFlow = PlaybackManager.currentTimeline,
                chapterContextFlow = PlaybackManager.useChapterContext,
                playableFlow = PlaybackManager.currentPlayable,
                chapterIndexFlow = PlaybackManager.currentChapterIndex,
                scope = serviceScope
            )

            mediaSession = MediaLibrarySession.Builder(this, sessionPlayer, CustomMediaLibrarySessionCallback())
                .setSessionActivity(pendingIntent)
                .setMediaButtonPreferences(buildMediaButtonPreferences())
                // Load notification artwork through the same data source factory as playback, so
                // external-server covers (auth via headers, not URL tokens) render in the media
                // notification too.
                .setBitmapLoader(
                    CacheBitmapLoader(
                        DataSourceBitmapLoader(DataSourceBitmapLoader.DEFAULT_EXECUTOR_SERVICE.get(), dataSourceFactory)
                    )
                )
                .build()

            // Initialize LoudnessEnhancer
            try {
                loudnessEnhancer = LoudnessEnhancer(p.audioSessionId)
                loudnessEnhancer?.setTargetGain(1000) // 10dB boost (approx double loudness)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Observe volume boost setting
        serviceScope.launch {
            PlaybackSettingsManager.getVolumeBoost(this@AudioPlayerService).collectLatest { enabled ->
                try {
                    loudnessEnhancer?.enabled = enabled
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // Keep Android Auto's Recent tab fresh: Auto caches a browse node's children, so when the playing
        // book changes we must tell browsers the "recent" node changed → Auto re-queries onGetChildren.
        serviceScope.launch {
            PlaybackManager.currentItem
                .map { it?.uuid }
                .distinctUntilChanged()
                .drop(1) // skip the value already present at connect
                .collect {
                    val session = mediaSession ?: return@collect
                    val count = withContext(Dispatchers.IO) {
                        AppDatabase.getDatabase(this@AudioPlayerService)
                            .libraryDao().getRecentPlayedItemsSync(RECENT_LIMIT).size
                    }
                    session.notifyChildrenChanged(MediaBrowseTree.RECENT_ID, count.coerceAtLeast(1), null)
                }
        }

        // Keep the Now Playing speed button's glyph in sync with the live playback speed.
        serviceScope.launch {
            PlaybackManager.playbackSpeed.drop(1).collect { refreshMediaButtons() }
        }
    }

    /** Rebuild + push the Now Playing custom-button row to reflect current speed / chapter / bookmark state. */
    private fun refreshMediaButtons() {
        mediaSession?.setMediaButtonPreferences(buildMediaButtonPreferences())
    }

    /**
     * The Now Playing custom-button row. Transport rewind/fast-forward stay in the flanking
     * SLOT_BACK/SLOT_FORWARD (never replaced); the overflow row carries `speed · bookmark`. The speed
     * glyph reflects the live speed.
     */
    private fun buildMediaButtonPreferences(): List<CommandButton> {
        // Rewind / fast-forward: Media3's circular curved-arrow ICON_SKIP_BACK/FORWARD (matching iOS's
        // arrow.counterclockwise/clockwise). CUSTOM session-command buttons — not player seek commands
        // (those are withheld in onConnect) — so System UI can't swap in skip-track glyphs. Taps route
        // through onCustomCommand; Bluetooth gestures through onMediaButtonEvent.
        val rewind = CommandButton.Builder(CommandButton.ICON_SKIP_BACK)
            .setSessionCommand(SessionCommand(APP_ACTION_REWIND, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_BACK)
            .setDisplayName(getString(R.string.media_action_rewind))
            .build()
        val forward = CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD)
            .setSessionCommand(SessionCommand(APP_ACTION_FORWARD, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_FORWARD)
            .setDisplayName(getString(R.string.media_action_fast_forward))
            .build()

        fun overflow(action: String, icon: Int, name: String): CommandButton =
            CommandButton.Builder(icon)
                .setSessionCommand(SessionCommand(action, Bundle.EMPTY))
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .setDisplayName(name)
                .build()

        // Overflow row: speed (glyph reflects the live rate) then add-bookmark. The bookmark button
        // stays the outline "add" affordance — feedback comes from the confirmation toast, not an
        // icon toggle.
        return buildList {
            add(rewind)
            add(overflow(APP_ACTION_CYCLE_SPEED, speedIcon(PlaybackManager.playbackSpeed.value), getString(R.string.auto_action_speed)))
            add(overflow(APP_ACTION_BOOKMARK, CommandButton.ICON_BOOKMARK_UNFILLED, getString(R.string.auto_action_bookmark)))
            add(forward)
        }
    }

    /** Nearest Media3 built-in speed glyph for [speed], so the speed button visibly shows the rate. */
    private fun speedIcon(speed: Float): Int = when {
        speed < 0.65f -> CommandButton.ICON_PLAYBACK_SPEED_0_5
        speed < 0.9f -> CommandButton.ICON_PLAYBACK_SPEED_0_8
        speed < 1.1f -> CommandButton.ICON_PLAYBACK_SPEED_1_0
        speed < 1.35f -> CommandButton.ICON_PLAYBACK_SPEED_1_2
        speed < 1.65f -> CommandButton.ICON_PLAYBACK_SPEED_1_5
        speed < 1.9f -> CommandButton.ICON_PLAYBACK_SPEED_1_8
        else -> CommandButton.ICON_PLAYBACK_SPEED_2_0
    }

    /**
     * Add a bookmark at the current position and surface a transient toast on the car screen. Android
     * Auto renders a session error's message as a toast — the only supported way to show text feedback
     * (media apps can't present custom dialogs/alerts).
     */
    private fun handleBookmark(session: MediaSession, controller: MediaSession.ControllerInfo) {
        serviceScope.launch {
            val message = when (val outcome = PlaybackManager.createBookmarkAtCurrentPosition(this@AudioPlayerService)) {
                is PlaybackManager.BookmarkOutcome.Created ->
                    getString(R.string.auto_bookmark_added, formatBookmarkTime(outcome.timeSeconds))
                is PlaybackManager.BookmarkOutcome.Existed ->
                    getString(R.string.auto_bookmark_exists)
                PlaybackManager.BookmarkOutcome.Failed -> getString(R.string.auto_bookmark_failed)
            }
            session.sendError(controller, SessionError(SessionError.ERROR_UNKNOWN, message))
        }
    }

    /** Whole-book position as H:MM:SS (or M:SS under an hour) for the bookmark confirmation toast. */
    private fun formatBookmarkTime(seconds: Double): String {
        val total = seconds.toLong()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }

    /**
     * CPU-only wake lock for local files; CPU + Wi-Fi lock only when the item streams from a remote
     * URL (so the Wi-Fi radio isn't held awake during local playback).
     */
    private fun wakeModeFor(mediaItem: MediaItem?): Int {
        val scheme = mediaItem?.localConfiguration?.uri?.scheme?.lowercase()
        return if (scheme == "http" || scheme == "https") C.WAKE_MODE_NETWORK else C.WAKE_MODE_LOCAL
    }

    /**
     * Seek the player by the live configured interval. Delegates to PlaybackManager so a BOUND book's
     * skip crosses sub-book (chapter) boundaries on the whole-book timeline instead of clamping inside
     * the current file — same behavior as the in-app transport controls.
     */
    private fun seekRelative(forward: Boolean) {
        val p = player ?: return
        val seconds = if (forward) PlaybackManager.forwardInterval.value else PlaybackManager.rewindInterval.value
        val deltaMs = seconds * 1000L
        PlaybackManager.seekRelativeAcrossChapters(p, if (forward) deltaMs else -deltaMs)
    }

    private inner class CustomMediaLibrarySessionCallback : MediaLibrarySession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            // Withhold the standard skip / relative-seek player commands so System UI can't replace
            // our custom rewind / fast-forward icons with its own skip-track glyphs. Everything else
            // (play/pause, scrub via seek-in-current-item, speed, volume, media-item changes) stays.
            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .remove(Player.COMMAND_SEEK_TO_NEXT)
                .remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .remove(Player.COMMAND_SEEK_TO_PREVIOUS)
                .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .remove(Player.COMMAND_SEEK_BACK)
                .remove(Player.COMMAND_SEEK_FORWARD)
                .build()
            // Advertise our custom rewind / fast-forward actions to controllers. Start from the
            // library-INCLUSIVE default set: for a MediaLibrarySession, DEFAULT_SESSION_COMMANDS omits the
            // browse (library) commands, so a MediaBrowser (Android Auto) would be PERMISSION_DENIED on
            // getLibraryRoot/getChildren and never render the browse tree.
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(SessionCommand(APP_ACTION_REWIND, Bundle.EMPTY))
                .add(SessionCommand(APP_ACTION_FORWARD, Bundle.EMPTY))
                .add(SessionCommand(APP_ACTION_CYCLE_SPEED, Bundle.EMPTY))
                .add(SessionCommand(APP_ACTION_BOOKMARK, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailablePlayerCommands(playerCommands)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                APP_ACTION_REWIND -> seekRelative(forward = false)
                APP_ACTION_FORWARD -> seekRelative(forward = true)
                // Speed persists → the playbackSpeed observer rebuilds the button with the new glyph.
                APP_ACTION_CYCLE_SPEED -> PlaybackManager.cyclePlaybackSpeed(this@AudioPlayerService)
                APP_ACTION_BOOKMARK -> handleBookmark(session, controller)
                else -> return super.onCustomCommand(session, controller, customCommand, args)
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent
        ): Boolean {
            val keyEvent = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                ?: return super.onMediaButtonEvent(session, controllerInfo, intent)
            // Remap the Bluetooth/headset gestures to seek-by-interval (audiobook-friendly):
            // NEXT (2 taps) / dedicated fast-forward -> forward; PREVIOUS (3 taps) / rewind -> back.
            // We consume both the down and up events for these keys (acting once, on key-down) so the
            // default skip handling never runs.
            return when (keyEvent.keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> {
                    if (keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0) {
                        seekRelative(forward = true)
                    }
                    true
                }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_MEDIA_REWIND,
                KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> {
                    if (keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0) {
                        seekRelative(forward = false)
                    }
                    true
                }
                else -> super.onMediaButtonEvent(session, controllerInfo, intent)
            }
        }

        // --- Android Auto / MediaBrowser browse tree (mirrors iOS CarPlay: Recent + Library tabs) ---

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            // Ask Android Auto to render every node as a LIST (title + author + thumbnail), matching
            // iOS CarPlay. Set on the browser-root extras so it applies globally to all children;
            // individual items can still override via their own metadata extras.
            val rootExtras = Bundle().apply {
                putInt(
                    MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                    MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
                )
                putInt(
                    MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                    MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
                )
            }
            val rootParams = LibraryParams.Builder().setExtras(rootExtras).build()
            return Futures.immediateFuture(
                LibraryResult.ofItem(browsableItem(MediaBrowseTree.ROOT_ID, getString(R.string.app_name)), rootParams)
            )
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            serviceScope.launch(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(this@AudioPlayerService)
                val dao = db.libraryDao()
                val node = MediaBrowseTree.parse(parentId)

                // Root → the fixed Recent + Library tabs (never paginated).
                if (node is MediaBrowseTree.Node.Root) {
                    val tabs = listOf(
                        browsableItem(MediaBrowseTree.RECENT_ID, getString(R.string.auto_tab_recent)),
                        browsableItem(MediaBrowseTree.LIBRARY_ID, getString(R.string.library_title_default))
                    )
                    future.set(LibraryResult.ofItemList(ImmutableList.copyOf(tabs), params))
                    return@launch
                }

                val all: List<LibraryItemEntity> = when (node) {
                    MediaBrowseTree.Node.Recent -> {
                        val recent = dao.getRecentPlayedItemsSync(RECENT_LIMIT).toMutableList()
                        // Show the currently-playing book at the top even before its lastPlayDate is
                        // persisted (written on the first progress tick, up to ~10s after play starts).
                        PlaybackManager.currentItem.value?.let { current ->
                            if (MediaBrowseTree.isPlayable(current.type) && recent.none { it.uuid == current.uuid }) {
                                recent.add(0, current)
                            }
                        }
                        recent
                    }
                    MediaBrowseTree.Node.Library -> dao.getRootItemsSync()
                    is MediaBrowseTree.Node.Folder -> dao.getItemsInPathSync(node.relativePath)
                    else -> emptyList()
                }

                // Empty node → a single, non-actionable info row (only on the first page) instead of
                // Auto's generic blank screen.
                if (all.isEmpty()) {
                    val info = if (page == 0) listOf(emptyStateItem(node)) else emptyList()
                    future.set(LibraryResult.ofItemList(ImmutableList.copyOf(info), params))
                    return@launch
                }

                // Honor Auto's page/pageSize instead of silently truncating a large library.
                val pageEntities = paginate(all, page, pageSize)
                // Resolves local/cached/sub-book art synchronously; remote art is prefetched below so
                // the list isn't blocked on network.
                val children = pageEntities.mapNotNull { toMediaItem(it, resolveBrowseArtworkUri(it)) }
                grantArtworkRead(browser.packageName, children)
                future.set(LibraryResult.ofItemList(ImmutableList.copyOf(children), params))
                if (pageEntities.isNotEmpty()) prefetchRemoteArtwork(parentId, pageEntities)
            }
            return future
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val future = SettableFuture.create<LibraryResult<MediaItem>>()
            serviceScope.launch(Dispatchers.IO) {
                // Fixed nodes resolve to their browsable container (so onSubscribe on a tab succeeds).
                val fixed = when (MediaBrowseTree.parse(mediaId)) {
                    MediaBrowseTree.Node.Root -> browsableItem(MediaBrowseTree.ROOT_ID, getString(R.string.app_name))
                    MediaBrowseTree.Node.Recent -> browsableItem(MediaBrowseTree.RECENT_ID, getString(R.string.auto_tab_recent))
                    MediaBrowseTree.Node.Library -> browsableItem(MediaBrowseTree.LIBRARY_ID, getString(R.string.library_title_default))
                    else -> null
                }
                if (fixed != null) {
                    future.set(LibraryResult.ofItem(fixed, null))
                    return@launch
                }
                val path = when (val node = MediaBrowseTree.parse(mediaId)) {
                    is MediaBrowseTree.Node.Item -> node.relativePath
                    is MediaBrowseTree.Node.Folder -> node.relativePath
                    else -> null
                }
                val item = path?.let { AppDatabase.getDatabase(this@AudioPlayerService).libraryDao().getItemByPath(it) }
                    ?.let { toMediaItem(it, resolveBrowseArtworkUri(it)) }
                if (item != null) grantArtworkRead(browser.packageName, listOf(item))
                future.set(
                    if (item != null) LibraryResult.ofItem(item, null)
                    else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
                )
            }
            return future
        }

        // Browse-play: when Auto sets a browse item (mediaId "item:<path>"), resolve it through
        // PlaybackManager (multi-file BOUND playlist, whole-book timeline, progress restore) and return the
        // REAL file items + start position so the session player plays them. We must return the resolved
        // items (not an empty list): an empty playlist that then gets prepared+played crashes
        // SimpleBasePlayer.getState ("Empty playlist only allowed in STATE_IDLE or STATE_ENDED"). The
        // future completes only after the model is built, so the player never transitions through empty.
        // PlaybackManager's OWN setMediaItems uses uuid mediaIds (parse -> Unknown) and falls through to super.
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val path = mediaItems.firstNotNullOfOrNull {
                (MediaBrowseTree.parse(it.mediaId) as? MediaBrowseTree.Node.Item)?.relativePath
            } ?: return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)

            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            serviceScope.launch(Dispatchers.Main) {
                val resolved = PlaybackManager.resolveSessionMediaItems(this@AudioPlayerService, path)
                if (resolved != null && resolved.items.isNotEmpty()) {
                    future.set(
                        MediaSession.MediaItemsWithStartPosition(resolved.items, resolved.startIndex, resolved.startPositionMs)
                    )
                } else {
                    // Stale/unresolvable browse row: keep the current playlist rather than clearing it
                    // (an empty result would crash getState if the player then prepares+plays).
                    android.util.Log.w("AudioPlayerService", "Auto browse-play: could not resolve '$path'")
                    future.set(currentMediaItemsWithStartPosition(mediaSession))
                }
            }
            return future
        }

        // --- Android Auto in-car search (mirrors iOS searchAllBooks: title OR author, incl. bound
        //     books, excludes folders). Auto calls onSearch first (we report the count), then pulls
        //     pages via onGetSearchResult. Result rows use the same "item:<path>" ids as browse, so a
        //     tap plays through the onSetMediaItems path already wired above. ---

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            val future = SettableFuture.create<LibraryResult<Void>>()
            serviceScope.launch(Dispatchers.IO) {
                val count = searchEntities(query).size
                session.notifySearchResultChanged(browser, query, count, params)
                future.set(LibraryResult.ofVoid(params ?: LibraryParams.Builder().build()))
            }
            return future
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            serviceScope.launch(Dispatchers.IO) {
                val pageEntities = paginate(searchEntities(query), page, pageSize)
                // Local/cached/sub-book art resolves synchronously; remote-only covers are left blank
                // here (search has no browse-node id to notifyChildrenChanged against for a backfill).
                val children = pageEntities.mapNotNull { toMediaItem(it, resolveBrowseArtworkUri(it)) }
                grantArtworkRead(browser.packageName, children)
                future.set(LibraryResult.ofItemList(ImmutableList.copyOf(children), params))
            }
            return future
        }
    }

    /** Run the Android Auto search query (empty → no results), capped to SEARCH_LIMIT. Off the main thread. */
    private suspend fun searchEntities(query: String): List<LibraryItemEntity> {
        if (query.isBlank()) return emptyList()
        return AppDatabase.getDatabase(this).libraryDao().searchAllBooksSync(query.trim(), SEARCH_LIMIT)
    }

    private fun currentMediaItemsWithStartPosition(session: MediaSession): MediaSession.MediaItemsWithStartPosition {
        val p = session.player
        val items = (0 until p.mediaItemCount).map { p.getMediaItemAt(it) }
        return MediaSession.MediaItemsWithStartPosition(items, p.currentMediaItemIndex, p.currentPosition.coerceAtLeast(0L))
    }

    /** A browsable container node (root / tab / folder). */
    private fun browsableItem(mediaId: String, title: String): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            .build()
        return MediaItem.Builder().setMediaId(mediaId).setMediaMetadata(metadata).build()
    }

    /** A library entity → browse MediaItem (folder=browsable, book/bound=playable). Null if no path.
     *  [artworkUri] is resolved by [resolveBrowseArtworkUri] (content:// so Auto's process can read it). */
    private fun toMediaItem(entity: LibraryItemEntity, artworkUri: Uri?): MediaItem? {
        val mediaId = MediaBrowseTree.mediaIdFor(entity.type, entity.relativePath) ?: return null
        val browsable = MediaBrowseTree.isBrowsable(entity.type)
        val metadata = MediaMetadata.Builder()
            .setTitle(entity.title)
            .setArtist(entity.author)
            .setIsBrowsable(browsable)
            .setIsPlayable(MediaBrowseTree.isPlayable(entity.type))
            .setMediaType(if (browsable) MediaMetadata.MEDIA_TYPE_FOLDER_MIXED else MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
            .setArtworkUri(artworkUri)
            // Playable books carry play-progress extras so Auto shows the familiar progress bar /
            // "played" checkmark on each row (parity with the in-app list).
            .apply { if (!browsable) setExtras(progressExtras(entity)) }
            .build()
        return MediaItem.Builder().setMediaId(mediaId).setMediaMetadata(metadata).build()
    }

    /**
     * Android Auto play-progress extras for a playable book, from the same `percentCompleted` /
     * `isFinished` the in-app list uses. `COMPLETION_PERCENTAGE` is a 0..1 double (our field already is).
     */
    private fun progressExtras(entity: LibraryItemEntity): Bundle = Bundle().apply {
        val status = when {
            entity.isFinished -> MediaConstants.DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED
            entity.percentCompleted > 0.0 -> MediaConstants.DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED
            else -> MediaConstants.DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED
        }
        putInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS, status)
        if (status == MediaConstants.DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED) {
            putDouble(
                MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_PERCENTAGE,
                entity.percentCompleted.coerceIn(0.0, 1.0)
            )
        }
    }

    /** A non-actionable info row shown when a browse node is empty. */
    private fun emptyStateItem(node: MediaBrowseTree.Node): MediaItem {
        val text = if (node is MediaBrowseTree.Node.Recent) {
            getString(R.string.auto_empty_recent)
        } else {
            getString(R.string.auto_empty_library)
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(text)
            .setIsBrowsable(false)
            .setIsPlayable(false)
            .build()
        return MediaItem.Builder().setMediaId(MediaBrowseTree.INFO_ID).setMediaMetadata(metadata).build()
    }

    /** Slice [all] for Auto's requested [page]/[pageSize]; empty past the end. */
    private fun paginate(all: List<LibraryItemEntity>, page: Int, pageSize: Int): List<LibraryItemEntity> {
        if (pageSize <= 0) return all
        val from = page * pageSize
        if (from >= all.size) return emptyList()
        return all.subList(from, minOf(from + pageSize, all.size))
    }

    /**
     * Local cover art as a `content://` URI so Android Auto's (separate) process can read it — it can't
     * open our app-private `file://` paths. Null when the file is missing or not under an exposed
     * FileProvider path (Auto couldn't read a raw file:// anyway, so null → no art, not a broken URI).
     */
    private fun localArtworkContentUri(path: String): Uri? {
        val file = File(path)
        if (!file.exists()) return null
        return try {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * Resolve a browse item's cover as a content:// URI Auto can read. The DB `artworkURL` is often null
     * (BOUND parents never get one; the in-app list falls back to on-demand embedded extraction via a Coil
     * fetcher that Auto's process can't use). Order: explicit stored art → previously-extracted cache →
     * BOUND: the first sub-book's art → extract embedded art from the downloaded file (cached under
     * Artworks/<uuid>.jpg). Returns null (no cover) for folders and not-downloaded/art-less items. Off main.
     */
    private suspend fun resolveBrowseArtworkUri(entity: LibraryItemEntity): Uri? = withContext(Dispatchers.IO) {
        entity.artworkURL?.let { url ->
            if (url.startsWith("http")) return@withContext Uri.parse(url) // Auto loads public/presigned http itself
            localArtworkContentUri(url)?.let { return@withContext it }
        }
        if (entity.type == ItemType.FOLDER) return@withContext null
        val artworksDir = File(filesDir, "Artworks")
        File(artworksDir, "${entity.uuid}.jpg").takeIf { it.isFile }
            ?.let { return@withContext localArtworkContentUri(it.absolutePath) }

        if (entity.type == ItemType.BOUND) {
            val firstSub = entity.relativePath?.let {
                AppDatabase.getDatabase(this@AudioPlayerService).libraryDao()
                    .getItemsInPathSync(it).firstOrNull { sub -> sub.type == ItemType.BOOK }
            } ?: return@withContext null
            firstSub.artworkURL?.takeIf { !it.startsWith("http") }
                ?.let { localArtworkContentUri(it)?.let { u -> return@withContext u } }
            File(artworksDir, "${firstSub.uuid}.jpg").takeIf { it.isFile }
                ?.let { return@withContext localArtworkContentUri(it.absolutePath) }
            return@withContext extractArtworkToCache(firstSub)
        }
        extractArtworkToCache(entity) // BOOK
    }

    /**
     * Stream embedded art from the REMOTE file for browse items that had none locally, cache it under
     * Artworks/<uuid>.jpg, then tell Auto the node changed so it re-queries (and the now-cached art
     * resolves). Runs in the background so the browse list isn't blocked on network. Bounded concurrency.
     */
    private suspend fun prefetchRemoteArtwork(parentId: String, entities: List<LibraryItemEntity>) {
        val artworksDir = File(filesDir, "Artworks")
        val processedDir = File(filesDir, "Processed")
        val dao = AppDatabase.getDatabase(this).libraryDao()
        var anyFetched = false
        for (entity in entities) {
            if (entity.type == ItemType.FOLDER) continue
            if (File(artworksDir, "${entity.uuid}.jpg").isFile) continue          // already cached
            val rp = entity.relativePath
            if (rp != null && File(processedDir, rp).isFile) continue             // local: handled synchronously
            val art = entity.artworkURL
            if (art != null && !art.startsWith("http")) continue                 // has a local artwork already
            // Remote file to read metadata from (BOUND: its first sub-book).
            val remoteUrl = when (entity.type) {
                ItemType.BOUND -> entity.relativePath?.let { path ->
                    dao.getItemsInPathSync(path).firstOrNull { it.type == ItemType.BOOK && !it.remoteURL.isNullOrEmpty() }?.remoteURL
                }
                else -> entity.remoteURL
            }
            if (remoteUrl.isNullOrEmpty()) continue
            artworksDir.mkdirs()
            val dest = File(artworksDir, "${entity.uuid}.jpg")
            val headers = PlaybackManager.getHeadersForUri(Uri.parse(remoteUrl))
            remoteArtSemaphore.withPermit {
                if (ArtworkManager.extractAndSaveArtworkFromUri(remoteUrl, headers, dest)) anyFetched = true
            }
        }
        if (anyFetched) withContext(Dispatchers.Main) {
            mediaSession?.notifyChildrenChanged(parentId, entities.size.coerceAtLeast(1), null)
        }
    }

    /** Extract embedded art from [entity]'s downloaded file into Artworks/<uuid>.jpg → content:// (or null). */
    private fun extractArtworkToCache(entity: LibraryItemEntity): Uri? {
        val relativePath = entity.relativePath ?: return null
        val audio = File(File(filesDir, "Processed"), relativePath)
        if (!audio.isFile) return null // not downloaded — remote streaming extraction is a later phase
        val dest = File(File(filesDir, "Artworks").apply { mkdirs() }, "${entity.uuid}.jpg")
        return if (ArtworkManager.extractAndSaveArtwork(audio, dest)) localArtworkContentUri(dest.absolutePath) else null
    }

    /** Grant the requesting browser (e.g. Android Auto) temporary read access to each item's content:// art. */
    private fun grantArtworkRead(browserPackage: String, items: List<MediaItem>) {
        items.forEach { mi ->
            val art = mi.mediaMetadata.artworkUri ?: return@forEach
            if (art.scheme == "content") {
                try {
                    grantUriPermission(browserPackage, art, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {
                    android.util.Log.w("AudioPlayerService", "grantUriPermission failed for $browserPackage", e)
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaSession
    }

    override fun onDestroy() {
        // Stop the settings + BookTimelinePlayer collectors before releasing the player, so a late
        // flow emission can't drive invalidateState()/getState() against a released ExoPlayer.
        serviceScope.cancel()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        player = null
        super.onDestroy()
    }

    companion object {
        private const val APP_ACTION_REWIND = "com.tortugapower.audiobookplayer.action.REWIND"
        private const val APP_ACTION_FORWARD = "com.tortugapower.audiobookplayer.action.FORWARD"
        // Android Auto Now Playing custom actions (speed cycle, add bookmark).
        private const val APP_ACTION_CYCLE_SPEED = "com.tortugapower.audiobookplayer.action.CYCLE_SPEED"
        private const val APP_ACTION_BOOKMARK = "com.tortugapower.audiobookplayer.action.BOOKMARK"
        // How many recently-played books the Recent tab surfaces (the tab is inherently bounded;
        // Library/Folder nodes are unbounded and paginated instead).
        private const val RECENT_LIMIT = 50
        // Cap total in-car search results (paginated on top of this).
        private const val SEARCH_LIMIT = 50
        // Bound concurrent remote artwork metadata streams during a browse prefetch.
        private val remoteArtSemaphore = Semaphore(3)
    }
}
