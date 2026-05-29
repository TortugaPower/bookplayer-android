package com.tortugapower.audiobookplayer.model

import com.google.gson.annotations.SerializedName

data class ContentsResponse(
    val content: List<SyncableItem>,
    val lastItemPlayed: SyncableItem?
)

data class SyncableItem(
    val uuid: String,
    val relativePath: String,
    val title: String,
    val details: String,
    val originalFileName: String,
    val duration: Double,
    val currentTime: Double,
    val percentCompleted: Double,
    val isFinished: Boolean,
    val orderRank: Int,
    val type: Int, // Map to ItemType ordinal
    @SerializedName("url") val remoteURL: String?,
    @SerializedName("thumbnail") val artworkURL: String?,
    val speed: Double?,
    @SerializedName("lastPlayDate") val lastPlayDateTimestamp: Double?
)

data class UploadItemResponse(
    val content: UploadItemContent
)

data class UploadItemContent(
    val url: String?
)

data class ArtworkResponse(
    @SerializedName("thumbnail_url") val thumbnailURL: String
)
