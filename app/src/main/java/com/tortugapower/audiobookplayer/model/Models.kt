package com.tortugapower.audiobookplayer.model

data class Chapter(
    val title: String,
    val author: String,
    val duration: Long,
    val playtimeOffset: Long = 0,
    val localPath: String? = null,
    val remoteUrl: String? = null,
    val externalResourceUrl: String? = null
)

data class PlayableItem(
    val chapters: List<Chapter>
)
