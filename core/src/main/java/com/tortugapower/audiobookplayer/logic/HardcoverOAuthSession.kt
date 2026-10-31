package com.tortugapower.audiobookplayer.logic

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tortugapower.audiobookplayer.network.HardcoverOAuth
import com.tortugapower.audiobookplayer.network.HardcoverOAuthException
import com.tortugapower.audiobookplayer.network.HardcoverTokenSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where a sign-in's tokens live. The app's is encrypted ([HardcoverAccount]); tests keep them in memory. */
interface HardcoverTokenStore {
    suspend fun read(): HardcoverTokenSet?
    suspend fun write(tokens: HardcoverTokenSet)
    suspend fun clear()
}

/** A token or revoke endpoint's answer. */
data class HardcoverHttpReply(val status: Int, val body: String)

/** The sign-in changed (signed out, or signed in again) while a refresh ran: its answer belongs to a sign-in that's gone. */
class HardcoverSignInChangedException : Exception("Hardcover sign-in changed during refresh")

/**
 * Owns the OAuth tokens and hands out a usable access token (iOS `HardcoverOAuthSession`). Refreshes run one at a
 * time: a refresh token works once, and sending a spent one again revokes the whole sign-in. Token requests are
 * never retried for the same reason. A refresh runs in [scope], not the caller's, so a caller leaving can't cancel
 * one whose new pair still has to be stored.
 */
class HardcoverOAuthSession(
    private val store: HardcoverTokenStore,
    /** Form POST to an OAuth endpoint (`client_id` is added here). Throws on a transport failure. */
    private val send: suspend (url: String, form: Map<String, String>) -> HardcoverHttpReply,
    private val clientId: () -> String = { HardcoverOAuth.clientId },
    private val now: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val mutex = Mutex()
    private var refreshing: CompletableDeferred<HardcoverTokenSet>? = null
    private var lastFailedRefresh: Long? = null

    suspend fun tokens(): HardcoverTokenSet? = store.read()

    suspend fun isSignedIn(): Boolean = store.read() != null

    /**
     * The access token to send, refreshed first when it's about to expire. Null when not signed in.
     * @param rejected a token the API just refused: it's refreshed even with time left, unless a refresh
     *   already replaced it.
     */
    suspend fun accessToken(rejected: String? = null): String? {
        val pending: CompletableDeferred<HardcoverTokenSet> = mutex.withLock {
            refreshing ?: run {
                val current = store.read() ?: return null
                val mustRefresh = if (rejected != null) {
                    rejected == current.accessToken
                } else {
                    current.needsRefresh(now()) && !isWaitingAfterFailedRefresh(current)
                }
                val refreshToken = current.refreshToken
                if (!mustRefresh || refreshToken == null) return current.accessToken

                val holder = CompletableDeferred<HardcoverTokenSet>()
                refreshing = holder
                scope.launch {
                    try {
                        holder.complete(refresh(current, refreshToken))
                    } catch (e: Throwable) {
                        holder.completeExceptionally(e)
                    } finally {
                        mutex.withLock { if (refreshing === holder) refreshing = null }
                    }
                }
                holder
            }
        }
        return pending.await().accessToken
    }

    /** Exchanges the code from the redirect and stores the new sign-in. */
    suspend fun signIn(code: String, codeVerifier: String): HardcoverTokenSet {
        val tokens = requestTokens(
            mapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "code_verifier" to codeVerifier,
                "redirect_uri" to HardcoverOAuth.REDIRECT_URI,
            )
        )
        mutex.withLock {
            // A refresh still running belongs to the old sign-in: it sees the new tokens and drops its answer.
            refreshing = null
            lastFailedRefresh = null
            store.write(tokens)
        }
        return tokens
    }

    /**
     * Forgets the sign-in, then revokes it. Revoking either token revokes both, and the endpoint always answers
     * 200, so a failure only leaves a token nobody holds.
     */
    suspend fun signOut() {
        val tokens = mutex.withLock {
            val tokens = store.read() ?: return
            refreshing = null
            lastFailedRefresh = null
            store.clear()
            tokens
        }
        try {
            send(HardcoverOAuth.REVOKE_URL, mapOf("token" to (tokens.refreshToken ?: tokens.accessToken)) + clientParam())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Ignored: the tokens are already gone here.
        }
    }

    private suspend fun refresh(current: HardcoverTokenSet, refreshToken: String): HardcoverTokenSet {
        try {
            val refreshed = requestTokens(mapOf("grant_type" to "refresh_token", "refresh_token" to refreshToken))
            mutex.withLock {
                if (store.read()?.refreshToken != refreshToken) throw HardcoverSignInChangedException()
                // Stored before anything else: the old refresh token is spent now.
                store.write(refreshed)
                lastFailedRefresh = null
            }
            return refreshed
        } catch (e: HardcoverOAuthException.InvalidGrant) {
            mutex.withLock { if (store.read()?.refreshToken == refreshToken) store.clear() }
            throw e
        } catch (e: HardcoverSignInChangedException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutex.withLock { lastFailedRefresh = now() }
            // Offline, rate limited or down: the access token may still have time left.
            if (!current.isExpired(now())) return current
            throw e
        }
    }

    private fun isWaitingAfterFailedRefresh(current: HardcoverTokenSet): Boolean {
        val failed = lastFailedRefresh ?: return false
        if (current.isExpired(now())) return false
        return now() - failed < HardcoverOAuth.REFRESH_RETRY_DELAY_MS
    }

    private fun clientParam() = mapOf("client_id" to clientId())

    private suspend fun requestTokens(params: Map<String, String>): HardcoverTokenSet {
        val reply = send(HardcoverOAuth.TOKEN_URL, params + clientParam())
        val body = runCatching { JsonParser.parseString(reply.body).asJsonObject }.getOrNull() ?: JsonObject()
        if (reply.status !in 200..299) {
            val error = body.get("error")?.takeIf { it.isJsonPrimitive }?.asString
            if (error != null) {
                if (error == "invalid_grant") throw HardcoverOAuthException.InvalidGrant()
                val description = body.get("error_description")?.takeIf { it.isJsonPrimitive }?.asString
                throw HardcoverOAuthException.Server(error, description)
            }
            throw HardcoverOAuthException.Http(reply.status)
        }
        return HardcoverTokenSet.fromResponse(body, now())
    }
}
