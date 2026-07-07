package com.tortugapower.audiobookplayer.service

import android.app.PendingIntent
import android.content.Intent
import android.media.audiofx.LoudnessEnhancer
import android.os.Bundle
import android.view.KeyEvent
import androidx.core.content.IntentCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.tortugapower.audiobookplayer.logic.PlaybackManager
import com.tortugapower.audiobookplayer.logic.PlaybackSettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Abstract Media3 playback service shared by the phone ([com.tortugapower.audiobookplayer.service]'s
 * `AudioPlayerService`) and the Wear standalone player. It owns everything that is identical on both
 * targets — Media3/ExoPlayer/audio-focus behave the same on a phone and a watch, unlike iOS which had to
 * fork its watch player for `AVAudioSession`:
 *
 *  - the ExoPlayer build (audio attributes + focus, "becoming noisy", per-item wake mode);
 *  - the auth [HttpDataSource.Factory] that attaches [PlaybackManager]'s per-host headers so
 *    external-server streams (and their notification cover art) authenticate;
 *  - the [BookTimelinePlayer] wrap that gives the OS notification / lock-screen scrubber the in-app
 *    player's whole-book / chapter context;
 *  - the [LoudnessEnhancer] volume boost, wired to the shared volume-boost setting;
 *  - the shared [BaseLibrarySessionCallback] (command withholding + rewind/fast-forward/speed custom
 *    actions + Bluetooth media-button remap) and Bluetooth/headset seek behavior.
 *
 * A concrete target subclasses this and supplies only its own pieces: the session-launch [PendingIntent]
 * ([createSessionActivity]), the Now Playing custom-button row with its localized names
 * ([buildMediaButtonPreferences]), and the session callback ([createSessionCallback] — typically a
 * [BaseLibrarySessionCallback] subclass adding a browse tree / target-specific custom actions). Only the
 * concrete subclass is a registered `<service>` in a manifest; this base never is.
 */
abstract class MediaPlaybackService : MediaLibraryService() {

    protected var player: ExoPlayer? = null
        private set
    protected var mediaSession: MediaLibrarySession? = null
        private set
    private var loudnessEnhancer: LoudnessEnhancer? = null
    protected val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** The Activity to launch when the user taps the media notification (phone: opens the player). */
    protected abstract fun createSessionActivity(): PendingIntent?

    /** The session callback — subclass a [BaseLibrarySessionCallback] to keep the shared transport
     *  behavior and add browse / target-specific custom actions on top. */
    protected abstract fun createSessionCallback(): MediaLibrarySession.Callback

    /** The Now Playing custom-button row. Localized display names live in the target's resources, so
     *  this is target-supplied; rebuilt on speed change via [refreshMediaButtons]. */
    protected abstract fun buildMediaButtonPreferences(): List<CommandButton>

    /** Called once the session is built, for target-specific observers (phone: Android Auto Recent
     *  refresh). The shared volume-boost and speed observers are already running by this point. */
    protected open fun onSessionReady() {}

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

            val builder = MediaLibrarySession.Builder(this, sessionPlayer, createSessionCallback())
                .setMediaButtonPreferences(buildMediaButtonPreferences())
                // Load notification artwork through the same data source factory as playback, so
                // external-server covers (auth via headers, not URL tokens) render in the media
                // notification too.
                .setBitmapLoader(
                    CacheBitmapLoader(
                        DataSourceBitmapLoader(DataSourceBitmapLoader.DEFAULT_EXECUTOR_SERVICE.get(), dataSourceFactory)
                    )
                )
            createSessionActivity()?.let { builder.setSessionActivity(it) }
            mediaSession = builder.build()

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
            PlaybackSettingsManager.getVolumeBoost(this@MediaPlaybackService).collectLatest { enabled ->
                try {
                    loudnessEnhancer?.enabled = enabled
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // Keep the Now Playing speed button's glyph in sync with the live playback speed.
        serviceScope.launch {
            PlaybackManager.playbackSpeed.drop(1).collect { refreshMediaButtons() }
        }

        onSessionReady()
    }

    /** Rebuild + push the Now Playing custom-button row to reflect current speed / chapter / bookmark state. */
    protected fun refreshMediaButtons() {
        mediaSession?.setMediaButtonPreferences(buildMediaButtonPreferences())
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
    protected fun seekRelative(forward: Boolean) {
        val p = player ?: return
        val seconds = if (forward) PlaybackManager.forwardInterval.value else PlaybackManager.rewindInterval.value
        val deltaMs = seconds * 1000L
        PlaybackManager.seekRelativeAcrossChapters(p, if (forward) deltaMs else -deltaMs)
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

    /**
     * The shared session callback: withholds the standard skip / relative-seek player commands (so
     * System UI can't replace our custom rewind / fast-forward glyphs), advertises the custom transport
     * actions, handles rewind / fast-forward / speed-cycle, and remaps Bluetooth/headset gestures to
     * seek-by-interval. A target extends this to add a browse tree ([MediaLibrarySession.Callback]'s
     * `onGetChildren` etc.) and its own custom actions ([customSessionCommands] + [onCustomSessionCommand]).
     */
    open inner class BaseLibrarySessionCallback : MediaLibrarySession.Callback {
        /** Custom session commands to advertise on connect, on top of rewind / forward / speed. Override
         *  to add target-specific actions (phone: add-bookmark). */
        protected open fun customSessionCommands(): List<SessionCommand> = emptyList()

        /** Handle a target-specific custom action (phone: add-bookmark). Return null to fall through to
         *  the framework default. Rewind / forward / speed are already handled by the base. */
        protected open fun onCustomSessionCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult>? = null

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
                .apply { customSessionCommands().forEach { add(it) } }
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
            return when (customCommand.customAction) {
                APP_ACTION_REWIND -> {
                    seekRelative(forward = false)
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                APP_ACTION_FORWARD -> {
                    seekRelative(forward = true)
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                // Speed persists → the playbackSpeed observer rebuilds the button with the new glyph.
                APP_ACTION_CYCLE_SPEED -> {
                    PlaybackManager.cyclePlaybackSpeed(this@MediaPlaybackService)
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                else -> onCustomSessionCommand(session, controller, customCommand, args)
                    ?: super.onCustomCommand(session, controller, customCommand, args)
            }
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
    }

    companion object {
        const val APP_ACTION_REWIND = "com.tortugapower.audiobookplayer.action.REWIND"
        const val APP_ACTION_FORWARD = "com.tortugapower.audiobookplayer.action.FORWARD"
        // Now Playing speed-cycle custom action (shared: phone Auto + Wear).
        const val APP_ACTION_CYCLE_SPEED = "com.tortugapower.audiobookplayer.action.CYCLE_SPEED"
    }
}
