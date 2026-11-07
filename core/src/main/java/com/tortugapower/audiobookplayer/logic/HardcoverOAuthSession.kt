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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
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
    /** That failure: an expired token's callers get it for the wait instead of each sending a refresh. */
    private var lastRefreshFailure: Exception? = null

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
                // Refused for good: no more refreshes. The token serves until it expires or is refused; logging out
                // and signing in again replaces it.
                if (current.refreshRefused) {
                    return current.accessToken.takeUnless { current.isExpired(now()) || it == rejected }
                }
                val refreshToken = current.refreshToken
                val mustRefresh = if (rejected != null) rejected == current.accessToken else current.needsRefresh(now())
                if (!mustRefresh || refreshToken == null) return current.accessToken
                // A refresh failed moments ago (offline, busy): wait it out rather than every caller sending one.
                // An expired token gets that failure; one with time left is still used.
                if (rejected == null) recentRefreshFailure()?.let { failure ->
                    if (current.isExpired(now())) throw failure
                    return current.accessToken
                }

                val holder = CompletableDeferred<HardcoverTokenSet>()
                refreshing = holder
                scope.launch {
                    val result = try {
                        Result.success(refresh(current, refreshToken))
                    } catch (e: Throwable) {
                        Result.failure(e)
                    }
                    // Cleared before its callers resume, so one asking again gets a fresh look at the stored token,
                    // never this finished refresh's answer (by then maybe expired). Even when cancelled: its callers
                    // must still get an answer.
                    withContext(NonCancellable) { mutex.withLock { if (refreshing === holder) refreshing = null } }
                    result.fold({ holder.complete(it) }, { holder.completeExceptionally(it) })
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
            lastRefreshFailure = null
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
            lastRefreshFailure = null
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
            // A refresh answer may leave out the refresh token and the scope (RFC 6749 §6): both stay as they were then.
            val refreshed = requestTokens(mapOf("grant_type" to "refresh_token", "refresh_token" to refreshToken)).let {
                it.copy(refreshToken = it.refreshToken ?: refreshToken, scope = it.scope.ifEmpty { current.scope })
            }
            mutex.withLock {
                if (store.read()?.refreshToken != refreshToken) throw HardcoverSignInChangedException()
                // Stored before anything else: the old refresh token is spent now.
                store.write(refreshed)
                lastFailedRefresh = null
                lastRefreshFailure = null
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
            val refused = e is HardcoverOAuthException && !e.isTemporary
            mutex.withLock {
                lastFailedRefresh = now()
                lastRefreshFailure = e
                // Marked on this sign-in only: a new one signed in meanwhile starts clean
                if (refused) store.read()?.takeIf { it.refreshToken == refreshToken }?.let { store.write(it.copy(refreshRefused = true)) }
            }
            // The access token may still have time left
            if (!current.isExpired(now())) return current
            throw e
        }
    }

    /** The refresh failure of the last [HardcoverOAuth.REFRESH_RETRY_DELAY_MS], if any. */
    private fun recentRefreshFailure(): Exception? {
        val failed = lastFailedRefresh ?: return null
        return lastRefreshFailure.takeIf { now() - failed < HardcoverOAuth.REFRESH_RETRY_DELAY_MS }
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
