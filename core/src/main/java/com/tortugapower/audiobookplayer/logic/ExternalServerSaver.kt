package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.entities.ExternalServerEntity
import com.tortugapower.audiobookplayer.database.entities.ExternalServiceType
import com.tortugapower.audiobookplayer.repository.ExternalServerRepository
import kotlinx.coroutines.flow.first

/**
 * Persists a successful media-server sign-in — the one place a new token lands in Room, whatever the
 * sign-in method was (password now; Quick Connect and SSO later), so de-duplication and re-auth
 * semantics can't drift between entry points. Mirrors iOS's `persist(...)` + store `upsert`.
 */
object ExternalServerSaver {
    data class SignIn(
        val type: ExternalServiceType,
        val name: String,
        val url: String,
        val username: String?,
        val token: String?,
        val headers: Map<String, String>?,
        val stableId: String?,
        val userId: String?,
        /** The row a re-authentication started from — see [ExternalServerUpsert]. */
        val replacingId: Long? = null,
    )

    data class Result(
        val server: ExternalServerEntity,
        /**
         * The row this sign-in replaced, when its token differs and the integration revokes replaced
         * tokens (AudiobookShelf: `POST /logout` with the OLD Bearer; Jellyfin deliberately doesn't
         * revoke on re-auth). The caller fires the revocation — best-effort, in its own scope.
         */
        val staleTokenToRevoke: ExternalServerEntity?,
    )

    suspend fun save(repository: ExternalServerRepository, signIn: SignIn): Result {
        // Anonymous connects arrive as "" from the form; store null so the UI's
        // `username ?: <anonymous>` fallbacks actually fire.
        val normalizedUsername = signIn.username?.takeIf { it.isNotBlank() }

        // Re-adding the same logical server + account (the natural response to an expired token)
        // replaces the existing row — preserving its id — instead of accumulating duplicates; a
        // server that moved host updates the row the re-auth started from. Mirrors iOS.
        val existing = ExternalServerUpsert.rowToReplace(
            repository.allServers.first(),
            ExternalServerUpsert.Incoming(
                type = signIn.type,
                url = signIn.url,
                username = normalizedUsername,
                userId = signIn.userId,
                replacingId = signIn.replacingId,
            )
        )

        val server = ExternalServerEntity(
            id = existing?.id ?: 0,
            name = signIn.name,
            type = signIn.type,
            url = signIn.url,
            username = normalizedUsername,
            token = signIn.token,
            customHeaders = signIn.headers?.takeIf { it.isNotEmpty() },
            // Re-auth keeps the user's library choice, same as iOS.
            selectedLibraryId = existing?.selectedLibraryId,
            // Re-auth refreshes the server's self-reported stable id — but a connect whose info
            // call happened to fail must not wipe a previously captured one.
            stableId = signIn.stableId ?: existing?.stableId,
            userId = signIn.userId ?: existing?.userId,
        )

        return if (existing != null) {
            repository.updateServer(server)
            val stale = existing.takeIf {
                signIn.type == ExternalServiceType.AUDIOBOOKSHELF && it.token != null && it.token != signIn.token
            }
            Result(server, stale)
        } else {
            val id = repository.saveServer(server)
            Result(server.copy(id = id), null)
        }
    }
}
