package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.entities.ExternalServerEntity
import com.tortugapower.audiobookplayer.database.entities.ExternalServiceType

/**
 * Which saved row a fresh sign-in replaces. Mirrors iOS `IntegrationConnectionStore.upsert`:
 *
 * 1. The same account on the same logical server (canonical URL + account) — the natural response to
 *    an expired token — replaces its row, keeping the id, library choice and stable id.
 * 2. Otherwise, the row a re-authentication *started from* ([Incoming.replacingId]) is replaced when its
 *    account matches: a server that moved host (self-hosters do this constantly) signs into an account no
 *    row matches by URL, and without this the old row would survive as an expired orphan next to the new
 *    one. A different account is genuinely a new connection, not a move — it forks by design.
 *
 * Account identity is the server's user id when both sides have one; rows saved before the column
 * existed (or servers that never reported an id) fall back to the username.
 */
object ExternalServerUpsert {
    data class Incoming(
        val type: ExternalServiceType,
        val url: String,
        val username: String?,
        val userId: String?,
        val replacingId: Long? = null,
    )

    fun rowToReplace(existing: List<ExternalServerEntity>, incoming: Incoming): ExternalServerEntity? {
        val urlKey = ExternalServiceUtils.canonicalServerKey(incoming.url)
        val sameAccountOnServer = existing.firstOrNull {
            it.type == incoming.type &&
                ExternalServiceUtils.canonicalServerKey(it.url) == urlKey &&
                isSameAccount(it, incoming)
        }
        if (sameAccountOnServer != null) return sameAccountOnServer
        val replacingId = incoming.replacingId ?: return null
        return existing.firstOrNull { it.id == replacingId && it.type == incoming.type && isSameAccount(it, incoming) }
    }

    fun isSameAccount(row: ExternalServerEntity, incoming: Incoming): Boolean {
        val rowUserId = row.userId
        val incomingUserId = incoming.userId
        return if (rowUserId != null && incomingUserId != null) {
            rowUserId == incomingUserId
        } else {
            row.username == incoming.username
        }
    }
}
