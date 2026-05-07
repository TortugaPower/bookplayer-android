package com.tortugapower.audiobookplayer.model

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player

fun Chapter.toMediaItem(): MediaItem {
    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(author)
        .setDurationMs(duration)
        .build()

    val uri = localPath?.let { Uri.parse(it) } 
        ?: remoteUrl?.let { Uri.parse(it) }
        ?: externalResourceUrl?.let { Uri.parse(it) }
        ?: Uri.EMPTY

    return MediaItem.Builder()
        .setMediaId(title) // Or a more unique ID if available
        .setUri(uri)
        .setMediaMetadata(metadata)
        .build()
}

fun PlayableItem.toMediaItems(): List<MediaItem> {
    return chapters.map { it.toMediaItem() }
}

fun Player.prepareWithPlayableItem(playableItem: PlayableItem, startChapterIndex: Int = 0) {
    val mediaItems = playableItem.toMediaItems()
    setMediaItems(mediaItems)
    
    val startChapter = playableItem.chapters.getOrNull(startChapterIndex)
    if (startChapter != null) {
        seekTo(startChapterIndex, startChapter.playtimeOffset)
    }
    prepare()
}
