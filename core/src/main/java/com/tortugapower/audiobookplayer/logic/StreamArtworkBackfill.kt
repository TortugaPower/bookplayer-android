package com.tortugapower.audiobookplayer.logic

import android.content.Context
import android.util.Log
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.dao.LibraryDao
import com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity
import com.tortugapower.audiobookplayer.database.entities.ExternalServerEntity
import com.tortugapower.audiobookplayer.database.entities.ExternalServiceType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.flow.first

/**
 * Backfills the cover of a stream-only media-server item on a device that didn't do the original
 * import. The importing device downloads the cover locally, but artwork never reaches our servers
 * (metadata uploads don't carry the device-local path, and LITE can't upload to S3) — so a second
 * device syncing the item gets `artworkURL = null` even though it can reach the same Jellyfin/ABS
 * server. This mirrors the import-time behavior: derive the provider's cover URL from the synced
 * `"stream"` resource (hostId + providerId), download it into `Artworks/`, and persist the local path.
 * Best-effort: failures leave `artworkURL` null, so the next fetch retries naturally.
 */
object StreamArtworkBackfill {

    /**
     * Provider cover request for [resource] against [server] (pure): URL + auth headers, or null for an
     * unknown provider. Uses the same endpoints as the browse listing (Jellyfin `Items/{id}/Images/Primary`,
     * ABS `api/items/{id}/cover`) and the same header auth as playback ([ExternalServiceUtils.playbackHeaders]).
     */
    fun artworkRequestFor(
        server: ExternalServerEntity,
        resource: ExternalResourceEntity,
    ): Pair<String, Map<String, String>?>? {
        val sanitizedUrl = ExternalServiceUtils.sanitizeUrl(server.url)
        val (path, type) = when (resource.providerName.lowercase()) {
            "jellyfin" -> "Items/${resource.providerId}/Images/Primary" to ExternalServiceType.JELLYFIN
            "audiobookshelf" -> "api/items/${resource.providerId}/cover" to ExternalServiceType.AUDIOBOOKSHELF
            else -> return null
        }
        return "$sanitizedUrl$path" to ExternalServiceUtils.playbackHeaders(type, server.token, server.customHeaders)
    }

    /**
     * Download the provider cover for [item] if it has a `"stream"` resource and no artwork yet.
     * Returns true when artwork was backfilled (item row updated).
     */
    suspend fun backfill(context: Context, libraryDao: LibraryDao, item: LibraryItemEntity): Boolean {
        if (!item.artworkURL.isNullOrBlank()) return false
        val resource = libraryDao.getExternalResourcesForBookSync(item.uuid)
            .find { it.syncStatus == ExternalResourceEntity.STATUS_STREAM } ?: return false

        // Same server resolution as playback (resolveStreamingUrl): hostId first, provider-type fallback.
        val serverDao = AppDatabase.getDatabase(context).externalServerDao()
        val server = resource.hostId?.toLongOrNull()?.let { serverDao.getServerById(it) }
            ?: run {
                val type = when (resource.providerName.lowercase()) {
                    "jellyfin" -> ExternalServiceType.JELLYFIN
                    "audiobookshelf" -> ExternalServiceType.AUDIOBOOKSHELF
                    else -> null
                }
                type?.let { t -> serverDao.getAllServers().first().find { it.type == t } }
            } ?: return false

        val (url, headers) = artworkRequestFor(server, resource) ?: return false

        return try {
            val artworkDir = File(context.filesDir, "Artworks")
            if (!artworkDir.exists()) artworkDir.mkdirs()
            val artworkFile = File(artworkDir, "${java.util.UUID.randomUUID()}.jpg")
            val requestBuilder = okhttp3.Request.Builder().url(url)
            headers?.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
            okhttp3.OkHttpClient().newCall(requestBuilder.build()).execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null) return false
                body.byteStream().use { input ->
                    FileOutputStream(artworkFile).use { output -> input.copyTo(output) }
                }
            }
            item.artworkURL = artworkFile.absolutePath
            libraryDao.updateItem(item)
            true
        } catch (e: Exception) {
            Log.w("StreamArtworkBackfill", "Cover backfill failed for ${item.title}", e)
            false
        }
    }
}
