package com.tortugapower.audiobookplayer.service

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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
import com.tortugapower.audiobookplayer.BookPlayerApplication
import com.tortugapower.audiobookplayer.MainActivity
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.logic.CoverArtResolver
import com.tortugapower.audiobookplayer.logic.PlaybackManager
import io.sentry.Breadcrumb
import io.sentry.Sentry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The phone playback service. Extends the shared [MediaPlaybackService] (which owns the ExoPlayer build,
 * auth data source, [BookTimelinePlayer] wrap, LoudnessEnhancer, and the transport-only session callback)
 * and adds the phone-only pieces: the launch-the-player [PendingIntent], the localized Now Playing
 * button row, add-bookmark, and the Android Auto / MediaBrowser browse tree (Recent + Library tabs, mirrors
 * iOS CarPlay). Registered as the phone's `<service>` in the manifest.
 */
class AudioPlayerService : MediaPlaybackService() {

    override fun createSessionActivity(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("OPEN_PLAYER", true)
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    override fun createSessionCallback(): MediaLibrarySession.Callback = CustomMediaLibrarySessionCallback()

    /**
     * media3 promotes this service with the media notification from here. "Bad notification for
     * startForeground" (Sentry ANDROID-BOOKPLAYER-1E) carries no cause on Android 12+, so leave a
     * breadcrumb on every promotion attempt: the next report will at least say which notification
     * was being posted.
     */
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        if (startInForegroundRequired) {
            Sentry.addBreadcrumb(
                Breadcrumb.info("promote AudioPlayerService (media notification, playing=${session.player.isPlaying})")
                    .apply { category = "fgs" }
            )
        }
        super.onUpdateNotification(session, startInForegroundRequired)
    }

    override fun onSessionReady() {
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

        // Same caching problem for the Library tab: when a sticky-sort preference changes (a pick
        // in the app, or a remote preference fetch), the cached node's order is stale. Coarse
        // invalidation: refresh the Library tab on any sort change; a folder node the browser is
        // currently inside refreshes on its next navigation.
        serviceScope.launch {
            BookPlayerApplication.instance.librarySortManager.observeSortPreferences()
                .distinctUntilChanged()
                .drop(1) // skip the snapshot already present at connect
                .collect {
                    val session = mediaSession ?: return@collect
                    val count = withContext(Dispatchers.IO) {
                        AppDatabase.getDatabase(this@AudioPlayerService)
                            .libraryDao().getRootItemsSync().size
                    }
                    session.notifyChildrenChanged(MediaBrowseTree.LIBRARY_ID, count.coerceAtLeast(1), null)
                }
        }
    }

    /**
     * The Now Playing custom-button row. Transport rewind/fast-forward stay in the flanking
     * SLOT_BACK/SLOT_FORWARD (never replaced); the overflow row carries `speed · bookmark`. The speed
     * glyph reflects the live speed.
     */
    override fun buildMediaButtonPreferences(): List<CommandButton> {
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
        // Explicit locale so digits render as ASCII (not e.g. Arabic-Indic) and lint's DefaultLocale is happy.
        val locale = java.util.Locale.getDefault()
        return if (h > 0) String.format(locale, "%d:%02d:%02d", h, m, s) else String.format(locale, "%d:%02d", m, s)
    }

    private inner class CustomMediaLibrarySessionCallback : BaseLibrarySessionCallback() {

        // Advertise the add-bookmark custom action on top of the shared rewind / forward / speed set.
        override fun customSessionCommands(): List<SessionCommand> =
            listOf(SessionCommand(APP_ACTION_BOOKMARK, Bundle.EMPTY))

        override fun onCustomSessionCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult>? {
            return if (customCommand.customAction == APP_ACTION_BOOKMARK) {
                handleBookmark(session, controller)
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            } else {
                null
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
                try {
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

                    // Library/folder nodes follow the location's effective sticky sort — the same
                    // view transform the app's list applies — BEFORE paginating, so page slices
                    // stay stable. Recent keeps recency order by design (matches the app's tab).
                    val ordered = when (node) {
                        MediaBrowseTree.Node.Library ->
                            BookPlayerApplication.instance.librarySortManager.sortedForDisplay(null, all)
                        is MediaBrowseTree.Node.Folder ->
                            BookPlayerApplication.instance.librarySortManager.sortedForDisplay(node.relativePath, all)
                        else -> all
                    }

                    // Honor Auto's page/pageSize instead of silently truncating a large library.
                    val pageEntities = paginate(ordered, page, pageSize)
                    // Resolves local/cached/sub-book art synchronously; remote art is prefetched below so
                    // the list isn't blocked on network.
                    val children = pageEntities.mapNotNull { toMediaItem(it, resolveBrowseArtworkUri(it)) }
                    grantArtworkRead(browser.packageName, children)
                    future.set(LibraryResult.ofItemList(ImmutableList.copyOf(children), params))
                    if (pageEntities.isNotEmpty()) prefetchRemoteArtwork(parentId, pageEntities)
                } catch (e: Exception) {
                    // Complete the future so Auto renders an error instead of spinning forever
                    // (serviceScope's SupervisorJob would otherwise swallow the failure).
                    android.util.Log.e("AudioPlayerService", "onGetChildren('$parentId') failed", e)
                    future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN))
                }
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
                try {
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
                } catch (e: Exception) {
                    android.util.Log.e("AudioPlayerService", "onGetItem('$mediaId') failed", e)
                    future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN))
                }
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
                try {
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
                } catch (e: Exception) {
                    // Never leave the future pending: fall back to the current playlist (never empty),
                    // same as the unresolvable-path branch, so play-from-browse can't hang the car.
                    android.util.Log.e("AudioPlayerService", "Auto browse-play resolve failed for '$path'", e)
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
                try {
                    val count = searchEntities(query).size
                    session.notifySearchResultChanged(browser, query, count, params)
                    future.set(LibraryResult.ofVoid(params ?: LibraryParams.Builder().build()))
                } catch (e: Exception) {
                    android.util.Log.e("AudioPlayerService", "onSearch('$query') failed", e)
                    future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN))
                }
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
                try {
                    val pageEntities = paginate(searchEntities(query), page, pageSize)
                    // Local/cached/sub-book art resolves synchronously; remote-only covers are left blank
                    // here (search has no browse-node id to notifyChildrenChanged against for a backfill).
                    val children = pageEntities.mapNotNull { toMediaItem(it, resolveBrowseArtworkUri(it)) }
                    grantArtworkRead(browser.packageName, children)
                    future.set(LibraryResult.ofItemList(ImmutableList.copyOf(children), params))
                } catch (e: Exception) {
                    android.util.Log.e("AudioPlayerService", "onGetSearchResult('$query') failed", e)
                    future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN))
                }
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
            // Containers store a bare child count as `author` — localize it for the Auto row.
            .setArtist(com.tortugapower.audiobookplayer.logic.LibraryContentsSync.displayDetails(this, entity.type, entity.author))
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
     * (BOUND parents never get one), so we fall back to the SHARED embedded-cover store via
     * [CoverArtResolver] — the same `Artworks/<uuid>.jpg` files the phone UI and notification use, so a
     * cover extracted on one surface shows on all of them. Local-only here (`includeRemote = false`): the
     * synchronous browse response must not block on the network; not-downloaded covers come via
     * [prefetchRemoteArtwork]. Returns null (no cover) for folders and not-downloaded/art-less items.
     */
    private suspend fun resolveBrowseArtworkUri(entity: LibraryItemEntity): Uri? = withContext(Dispatchers.IO) {
        entity.artworkURL?.let { url ->
            if (url.startsWith("http")) return@withContext Uri.parse(url) // Auto loads public/presigned http itself
            localArtworkContentUri(url)?.let { return@withContext it }
        }
        if (entity.type == ItemType.FOLDER) return@withContext null
        val dao = AppDatabase.getDatabase(this@AudioPlayerService).libraryDao()
        val file = CoverArtResolver.resolveCoverFile(this@AudioPlayerService, dao, entity, includeRemote = false)
            ?: return@withContext null
        localArtworkContentUri(file.absolutePath)
    }

    /**
     * Stream embedded art from the REMOTE file for browse items that had none locally (via the shared
     * [CoverArtResolver], which caches under Artworks/<uuid>.jpg and loops a BOUND item's sub-books), then
     * tell Auto the node changed so it re-queries and the now-cached art resolves. Background so the browse
     * list isn't blocked on network; the resolver bounds concurrency internally.
     */
    private suspend fun prefetchRemoteArtwork(parentId: String, entities: List<LibraryItemEntity>) {
        val dao = AppDatabase.getDatabase(this).libraryDao()
        var anyFetched = false
        for (entity in entities) {
            if (entity.type == ItemType.FOLDER) continue
            if (CoverArtResolver.cacheFile(this, entity.uuid).isFile) continue // already cached (any surface)
            if (CoverArtResolver.resolveCoverFile(this, dao, entity, includeRemote = true) != null) anyFetched = true
        }
        if (anyFetched) withContext(Dispatchers.Main) {
            mediaSession?.notifyChildrenChanged(parentId, entities.size.coerceAtLeast(1), null)
        }
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

    companion object {
        // Android Auto Now Playing add-bookmark custom action (phone-only; rewind / forward / speed live
        // in the shared MediaPlaybackService).
        private const val APP_ACTION_BOOKMARK = "com.tortugapower.audiobookplayer.action.BOOKMARK"
        // How many recently-played books the Recent tab surfaces (the tab is inherently bounded;
        // Library/Folder nodes are unbounded and paginated instead).
        private const val RECENT_LIMIT = 50
        // Cap total in-car search results (paginated on top of this).
        private const val SEARCH_LIMIT = 50
    }
}
