package com.tortugapower.audiobookplayer.model

import com.google.gson.annotations.SerializedName

data class ContentsResponse(
    @SerializedName("content") val content: List<SyncableItem>,
    @SerializedName("lastItemPlayed") val lastItemPlayed: SyncableItem?
)

data class SyncableItem(
    @SerializedName("uuid") val uuid: String?,
    @SerializedName("relativePath") val relativePath: String,
    @SerializedName("title") val title: String,
    @SerializedName("details") val details: String,
    @SerializedName("originalFileName") val originalFileName: String,
    @SerializedName("duration") val duration: Double,
    @SerializedName("currentTime") val currentTime: Double,
    @SerializedName("percentCompleted") val percentCompleted: Double,
    @SerializedName("isFinished") val isFinished: Boolean,
    @SerializedName("orderRank") val orderRank: Int,
    @SerializedName("type") val type: Int, // Map to ItemType ordinal
    @SerializedName("url") val remoteURL: String?,
    @SerializedName("thumbnail") val artworkURL: String?,
    @SerializedName("speed") val speed: Double?,
    // The API (and iOS) send this as "lastPlayDateTimestamp" — NOT "lastPlayDate" (that's the DB/upload
    // key). Reading the wrong key left every synced item's lastPlayDate null, so server-synced plays never
    // surfaced in the Wear recents / Android Auto Recent tab until played locally.
    @SerializedName("lastPlayDateTimestamp") val lastPlayDateTimestamp: Double?,
    @SerializedName("externalResources") val externalResources: List<SyncableExternalResource>? = null
)

data class SyncableExternalResource(
    @SerializedName("providerName") val providerName: String,
    @SerializedName("providerId") val providerId: String,
    @SerializedName("syncStatus") val syncStatus: String,
    // ISO-8601 string: unlike lastPlayDate (epoch-seconds integer column), the API's
    // last_synced_at is a timestamp column serialized as a JS Date.
    @SerializedName("lastSyncedAt") val lastSyncedAt: String?,
    @SerializedName("processedFile") val processedFile: Boolean,
    @SerializedName("hostId") val hostId: String?
)

data class UploadItemResponse(
    @SerializedName("content") val content: UploadItemContent
)

data class UploadItemContent(
    @SerializedName("url") val url: String?
)

data class ArtworkResponse(
    @SerializedName("thumbnail_url") val thumbnailURL: String
)

/**
 * `POST /v1/library/external_set` — request a presigned PUT URL for an external item's source file
 * (`{uuid}` → [url]), or confirm the upload (`{uuid, uploaded: true}` → [uploaded], empty [url]).
 */
data class ExternalSetResponse(
    @SerializedName("url") val url: String?,
    @SerializedName("uploaded") val uploaded: Boolean?
)

data class IdentifiersResponse(
    @SerializedName("content") val content: List<String>
)

data class MatchUuidsResponse(
    @SerializedName("applied") val applied: List<String>,
    @SerializedName("conflicts") val conflicts: List<ItemConflict>
)

data class ItemConflict(
    @SerializedName("key") val key: String, // Maps to the local sent "uuid" (our key in the map)
    @SerializedName("uuid") val uuid: String // Maps to the authoritative server uuid
)

/**
 * `GET /v1/library/bookmarks` (iOS `BookmarksResponse`): the server wraps the rows in a `bookmarks`
 * object, it is NOT a bare array. Each row is `{title, key, note, time, active}` — `time` is the whole
 * seconds the client uploaded (set_bookmark rounds), `key` the item's relativePath. No uuid comes back.
 */
data class BookmarksResponse(
    @SerializedName("bookmarks") val bookmarks: List<SyncableBookmark>
)

data class SyncableBookmark(
    @SerializedName("title") val title: String? = null,
    @SerializedName("key") val key: String?,
    @SerializedName("time") val time: Double,
    @SerializedName("note") val note: String?,
    // Deletes are soft (set_bookmark with active=false). The server's query already joins on
    // `b.active = true` (LibraryDB.getBookmarks), so inactive rows are not expected here; the merge
    // still skips them defensively so a bookmark deleted on this device can never be re-inserted.
    @SerializedName("active") val active: Boolean? = null
)
