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

data class ExternalLibraryItem(
    val entity: com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity,
    val genres: String? = null,
    val customHeaders: Map<String, String>? = null,
    // Filled when a stream import is prepared: the item's audio files when it has several (imported as a
    // volume), else empty.
    val streamFiles: List<com.tortugapower.audiobookplayer.network.StreamFile> = emptyList()
)
