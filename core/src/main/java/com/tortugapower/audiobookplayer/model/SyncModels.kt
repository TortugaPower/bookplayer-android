package com.tortugapower.audiobookplayer.model

import com.google.gson.annotations.SerializedName

data class ContentsResponse(
    val content: List<SyncableItem>,
    val lastItemPlayed: SyncableItem?
)

data class SyncableItem(
    val uuid: String?,
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
    // The API (and iOS) send this as "lastPlayDateTimestamp" — NOT "lastPlayDate" (that's the DB/upload
    // key). Reading the wrong key left every synced item's lastPlayDate null, so server-synced plays never
    // surfaced in the Wear recents / Android Auto Recent tab until played locally.
    @SerializedName("lastPlayDateTimestamp") val lastPlayDateTimestamp: Double?,
    val externalResources: List<SyncableExternalResource>? = null
)

data class SyncableExternalResource(
    val providerName: String,
    val providerId: String,
    val syncStatus: String,
    // ISO-8601 string: unlike lastPlayDate (epoch-seconds integer column), the API's
    // last_synced_at is a timestamp column serialized as a JS Date.
    val lastSyncedAt: String?,
    val processedFile: Boolean,
    val hostId: String?
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

data class IdentifiersResponse(
    val content: List<String>
)

data class MatchUuidsResponse(
    val applied: List<String>,
    val conflicts: List<ItemConflict>
)

data class ItemConflict(
    val key: String, // Maps to the local sent "uuid" (our key in the map)
    val uuid: String // Maps to the authoritative server uuid
)
