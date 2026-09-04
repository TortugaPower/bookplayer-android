package com.tortugapower.audiobookplayer.network

import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.model.ExternalLibraryItem

interface ExternalService {
    /**
     * Validates that a server is reachable at [url] and asks it which sign-in methods it offers, before
     * any credentials exist. Nothing is persisted; the result feeds the connection flow's routing
     * (`ConnectionRouting.decide`). Never throws for server/network failures — those come back as
     * [ProbeResult.Failure] with a typed [ConnectionError].
     */
    suspend fun probe(url: String, headers: Map<String, String>? = null): ProbeResult

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

/**
 * What a server told us it can do, so the UI only offers sign-in methods that can actually work.
 * Defaults are the failure-safe direction: when a probe can't answer, offering a password form that
 * might work beats hiding the only sign-in path a server may have.
 */
data class ServerCapabilities(
    /**
     * Whether the server accepts username/password at all. AudiobookShelf admins can disable local
     * auth outright (SSO-only servers), and `/status` then omits `"local"` from `authMethods`.
     * Jellyfin's core API always accepts it.
     */
    val supportsPassword: Boolean = true,
    /** AudiobookShelf: an OpenID provider is configured (`authMethods` contains `"openid"`). */
    val supportsOidc: Boolean = false,
    /** The provider button label ABS's own web UI shows (`authFormData.authOpenIDButtonText`), when set. */
    val oidcButtonText: String? = null,
    /** Jellyfin: `/QuickConnect/Enabled` answered `true`. Admins can switch the feature off. */
    val quickConnectEnabled: Boolean = false,
)

/**
 * A validated-but-not-yet-signed-in server: what the probe learned, held by the flow across the
 * Connect → sign-in transition. Mirrors iOS's `PendingServer` / `pingedURL`: credentials only ever go
 * to the address that was probed, so an edit between Connect and Sign In can't redirect them.
 */
data class PendingServer(
    /** The address as probed — the string [ExternalService.connect] and friends must be given. */
    val url: String,
    /** Jellyfin: the public `ServerName`. AudiobookShelf: the host (iOS parity — `/ping` carries no name; the login response does). */
    val serverName: String,
    /** The server's self-reported id when the probe can see it (Jellyfin public info `Id`); ABS only reports it at login. */
    val stableId: String?,
    val capabilities: ServerCapabilities,
)

sealed class ProbeResult {
    data class Found(val server: PendingServer) : ProbeResult()
    data class Failure(val error: ConnectionError) : ProbeResult()
}

/**
 * What the method screen offers besides typing a password — at most one, which is why this is a single
 * optional value rather than parallel booleans (the invalid "both at once" state is unrepresentable).
 */
sealed class AlternativeSignIn {
    /** Native SSO through the server's identity provider (AudiobookShelf OIDC). [buttonText] is the provider's own label when the server supplied one. */
    data class Oidc(val buttonText: String?) : AlternativeSignIn()

    /** Out-of-band code flow (Jellyfin Quick Connect). */
    data object QuickConnect : AlternativeSignIn()
}

/**
 * Implemented by services whose server offers Jellyfin-style Quick Connect: an out-of-band code the
 * user enters in an already-signed-in web session, exchanged here for a token. The flow only offers
 * the method when the probe reported it enabled ([ServerCapabilities.quickConnectEnabled]).
 */
interface QuickConnectCapable {
    /** A poller bound to [url] (and its custom headers); the caller owns its lifecycle. */
    fun quickConnect(url: String, headers: Map<String, String>?): com.tortugapower.audiobookplayer.logic.JellyfinQuickConnect

    /** Exchanges an approved Quick Connect secret for a session. Returns the same shape as [ExternalService.connect]. */
    suspend fun signInWithQuickConnect(url: String, secret: String, headers: Map<String, String>?): ConnectionResult
}

sealed class ConnectionResult {
    /**
     * [stableId] is the server's SELF-REPORTED unique id (Jellyfin `/System/Info` `Id`,
     * AudiobookShelf login `serverSettings.id`) — the cross-device half of the hostId contract
     * (`hostId := stableId ?: canonicalServerKey(url)`). Null when the server didn't report one
     * (old versions, info call failed): callers fall back to the canonical URL key.
     *
     * [userId] is the account's id on the server (Jellyfin `User.Id`, AudiobookShelf `user.id`) — the
     * identity connections are de-duplicated on, so two accounts on one server stay separate and a
     * re-auth of the same account replaces its row. Null when the response lacked it.
     */
    data class Success(
        val token: String? = null,
        val name: String? = null,
        val stableId: String? = null,
        val userId: String? = null,
        /** The account's display name from the auth response — Quick Connect never asks for one up front. */
        val userName: String? = null,
    ) : ConnectionResult()
    data class Failure(
        val message: String,
        val messageResId: Int? = null,
        val args: List<Any>? = null
    ) : ConnectionResult()
}
