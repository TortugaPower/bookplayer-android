package com.tortugapower.audiobookplayer.logic

import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.service.AudioPlayerService
import java.io.File

object PlaybackManager {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    var player: Player? by mutableStateOf(null)
        private set

    var currentItem: LibraryItemEntity? by mutableStateOf(null)
        private set

    var isPlaying by mutableStateOf(false)
        private set

    var showPlayerScreen by mutableStateOf(false)

    fun initialize(context: Context) {
        if (player != null) return

        val sessionToken = SessionToken(context, ComponentName(context, AudioPlayerService::class.java))
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture?.addListener({
            player = controllerFuture?.get()
            player?.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }
            })
        }, MoreExecutors.directExecutor())
    }

    fun playItem(context: Context, item: LibraryItemEntity) {
        currentItem = item
        val processedDir = File(context.filesDir, "Processed")
        val file = File(processedDir, item.relativePath ?: "")
        
        if (file.exists()) {
            val mediaItem = MediaItem.fromUri(file.absolutePath)
            player?.setMediaItem(mediaItem)
            player?.prepare()
            player?.play()
            showPlayerScreen = true
        }
    }

    fun togglePlayPause() {
        if (player?.isPlaying == true) {
            player?.pause()
        } else {
            player?.play()
        }
    }

    fun seekForward() {
        player?.seekTo((player?.currentPosition ?: 0) + 30000)
    }

    fun seekBackward() {
        player?.seekTo((player?.currentPosition ?: 0) - 30000)
    }

    fun release() {
        controllerFuture?.let {
            MediaController.releaseFuture(it)
        }
        player = null
        controllerFuture = null
    }
}
