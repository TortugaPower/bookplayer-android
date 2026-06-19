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
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.tortugapower.audiobookplayer.MainActivity
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.logic.PlaybackManager
import com.tortugapower.audiobookplayer.logic.PlaybackSettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

class AudioPlayerService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
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
                        val uri = dataSpec.uri
                        val key = uri.getQueryParameter("bp_header_key")
                        if (key != null) {
                            val headers = PlaybackManager.getHeaders(key)
                            headers?.forEach { (k, v) -> delegate.setRequestProperty(k, v) }
                            
                            val cleanUri = uri.buildUpon().clearQuery().apply {
                                uri.queryParameterNames.filter { it != "bp_header_key" }.forEach { name ->
                                    appendQueryParameter(name, uri.getQueryParameter(name))
                                }
                            }.build()
                            
                            return delegate.open(dataSpec.withUri(cleanUri))
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
            .setAudioAttributes(audioAttributes, false)
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
            })

            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("OPEN_PLAYER", true)
            }
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            // Spoken-audio media controls: rewind / play-pause / fast-forward, using Media3's native
            // ICON_SKIP_BACK / ICON_SKIP_FORWARD — the circular curved-arrow glyphs (matching iOS's
            // arrow.counterclockwise / arrow.clockwise), not the double-triangle ICON_REWIND/FAST_FORWARD.
            // These are CUSTOM session-command buttons — NOT player seek commands — and the standard
            // skip/seek player commands are withheld in onConnect, so System UI can't substitute its
            // own skip-track glyphs (the "right action, wrong icon" bug). Taps route through
            // onCustomCommand; Bluetooth gestures through onMediaButtonEvent — both seek by the live
            // configured interval.
            val rewindButton = CommandButton.Builder(CommandButton.ICON_SKIP_BACK)
                .setSessionCommand(SessionCommand(APP_ACTION_REWIND, Bundle.EMPTY))
                .setSlots(CommandButton.SLOT_BACK)
                .setDisplayName(getString(R.string.media_action_rewind))
                .build()
            val forwardButton = CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD)
                .setSessionCommand(SessionCommand(APP_ACTION_FORWARD, Bundle.EMPTY))
                .setSlots(CommandButton.SLOT_FORWARD)
                .setDisplayName(getString(R.string.media_action_fast_forward))
                .build()

            mediaSession = MediaSession.Builder(this, p)
                .setSessionActivity(pendingIntent)
                .setCallback(CustomMediaSessionCallback())
                .setMediaButtonPreferences(listOf(rewindButton, forwardButton))
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
    }

    /**
     * CPU-only wake lock for local files; CPU + Wi-Fi lock only when the item streams from a remote
     * URL (so the Wi-Fi radio isn't held awake during local playback).
     */
    private fun wakeModeFor(mediaItem: MediaItem?): Int {
        val scheme = mediaItem?.localConfiguration?.uri?.scheme?.lowercase()
        return if (scheme == "http" || scheme == "https") C.WAKE_MODE_NETWORK else C.WAKE_MODE_LOCAL
    }

    /** Seek the player relative to its current position by the live configured interval. */
    private fun seekRelative(forward: Boolean) {
        val p = player ?: return
        val seconds = if (forward) PlaybackManager.forwardInterval.value else PlaybackManager.rewindInterval.value
        val deltaMs = seconds * 1000L
        val target = p.currentPosition + (if (forward) deltaMs else -deltaMs)
        p.seekTo(target.coerceAtLeast(0L))
    }

    private inner class CustomMediaSessionCallback : MediaSession.Callback {
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
            // Advertise our custom rewind / fast-forward actions to controllers.
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(APP_ACTION_REWIND, Bundle.EMPTY))
                .add(SessionCommand(APP_ACTION_FORWARD, Bundle.EMPTY))
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
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
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
    }
}
