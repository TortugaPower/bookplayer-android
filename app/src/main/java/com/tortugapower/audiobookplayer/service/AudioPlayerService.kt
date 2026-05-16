package com.tortugapower.audiobookplayer.service

import android.app.PendingIntent
import android.content.Intent
import android.media.audiofx.LoudnessEnhancer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.tortugapower.audiobookplayer.MainActivity
import com.tortugapower.audiobookplayer.logic.PlaybackManager
import com.tortugapower.audiobookplayer.logic.PlaybackSettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AudioPlayerService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        player?.let { p ->
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("OPEN_PLAYER", true)
            }
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent, 
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            // Wrap player to handle next/previous commands even without a playlist
            val forwardingPlayer = object : ForwardingPlayer(p) {
                override fun getAvailableCommands(): Player.Commands {
                    return super.getAvailableCommands().buildUpon()
                        .add(Player.COMMAND_SEEK_TO_NEXT)
                        .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                        .build()
                }

                override fun isCommandAvailable(command: Int): Boolean {
                    return command == Player.COMMAND_SEEK_TO_NEXT || 
                           command == Player.COMMAND_SEEK_TO_PREVIOUS || 
                           super.isCommandAvailable(command)
                }

                override fun seekToNext() {
                    PlaybackManager.playNext(this@AudioPlayerService)
                }

                override fun seekToPrevious() {
                    PlaybackManager.playPrevious(this@AudioPlayerService)
                }
            }

            mediaSession = MediaSession.Builder(this, forwardingPlayer)
                .setSessionActivity(pendingIntent)
                .setCallback(CustomMediaSessionCallback())
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

    private inner class CustomMediaSessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val availablePlayerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .build()
            
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailablePlayerCommands(availablePlayerCommands)
                .build()
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
}
