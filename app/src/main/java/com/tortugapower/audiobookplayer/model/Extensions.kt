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

fun Long.formatSyncTime(): String {
    val diff = System.currentTimeMillis() - this
    if (diff < 0) return "0s"
    
    val seconds = (diff / 1000) % 60
    val minutes = (diff / (1000 * 60)) % 60
    val hours = (diff / (1000 * 60 * 60)) % 24
    val days = diff / (1000 * 60 * 60 * 24)
    
    return buildString {
        if (days > 0) append("${days}d ")
        if (hours > 0 || days > 0) append("${hours}h ")
        if (minutes > 0 || hours > 0 || days > 0) append("${minutes}m ")
        append("${seconds}s")
    }.trim()
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
