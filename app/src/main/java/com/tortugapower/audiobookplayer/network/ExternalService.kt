package com.tortugapower.audiobookplayer.network

import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.model.ExternalLibraryItem

interface ExternalService {
    suspend fun connect(url: String, username: String? = null, password: String? = null, headers: Map<String, String>? = null): ConnectionResult

    /**
     * The server's selectable libraries. Which libraries qualify is service-specific and mirrors
     * iOS deliberately: Jellyfin returns ALL user views with no media-type filter (audiobooks
     * often live in music-typed or untyped views, so filtering risks hiding the real library);
     * Audiobookshelf returns only `mediaType == "book"` libraries (drops podcasts).
     */
    suspend fun getLibraries(url: String, token: String, headers: Map<String, String>? = null): List<ExternalLibraryInfo>

    suspend fun getLibrary(url: String, token: String, startIndex: Int = 0, limit: Int = 50, headers: Map<String, String>? = null, libraryId: String? = null): LibraryResult
    suspend fun getStreamUrl(url: String, token: String, item: LibraryItemEntity): String
    suspend fun getThumbnailUrl(url: String, token: String, item: LibraryItemEntity): String?

    /**
     * Best-effort revocation of a token that is being discarded (server deleted, or replaced by a
     * re-auth). Fire-and-forget: implementations swallow failures — the token is being dropped
     * locally either way.
     */
    suspend fun revokeToken(url: String, token: String, headers: Map<String, String>? = null)
}

data class LibraryResult(
    val items: List<ExternalLibraryItem>,
    val totalCount: Int
)

/** A selectable library on an external server, as shown in the library picker. */
data class ExternalLibraryInfo(
    val id: String,
    val name: String,
    val artworkUrl: String? = null,
    /** Optional caption under the name (e.g. ABS shows "Audiobook library"); null for none. */
    val subtitleResId: Int? = null
)

/**
 * Thrown when an authenticated call to a SAVED external server returns 401/403 — the stored token
 * is no longer valid and the user must sign in again. Never thrown from [ExternalService.connect]
 * (pre-save sign-in failures stay [ConnectionResult.Failure], matching iOS).
 */
class SessionExpiredException : Exception("Session expired")

sealed class ConnectionResult {
    data class Success(val token: String? = null, val name: String? = null) : ConnectionResult()
    data class Failure(
        val message: String,
        val messageResId: Int? = null,
        val args: List<Any>? = null
    ) : ConnectionResult()
}
