package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.network.HardcoverOAuth
import com.tortugapower.audiobookplayer.network.HardcoverOAuthException
import com.tortugapower.audiobookplayer.network.HardcoverTokenSet
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Collections

/** iOS `HardcoverOAuthTests` (the session half): Hardcover's refresh rules. */
class HardcoverOAuthSessionTest {

    private class MemoryStore(var tokens: HardcoverTokenSet?) : HardcoverTokenStore {
        override suspend fun read() = tokens
        override suspend fun write(tokens: HardcoverTokenSet) { this.tokens = tokens }
        override suspend fun clear() { tokens = null }
    }

    private data class Sent(val url: String, val form: Map<String, String>)

    private var now = 1_000_000L
    private val sent = Collections.synchronizedList(mutableListOf<Sent>())
    private var reply: suspend (Sent) -> HardcoverHttpReply = { error("unexpected request") }

    private fun session(store: HardcoverTokenStore) = HardcoverOAuthSession(
        store = store,
        send = { url, form -> Sent(url, form).also { sent += it }.let { reply(it) } },
        clientId = { "client-1" },
        now = { now },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    private fun tokens(access: String = "hc_at_old", refresh: String? = "hc_rt_old", expiresIn: Long = 7 * 86_400_000L) =
        HardcoverTokenSet(access, refresh, "read:catalog read:library write:library read:me", now + expiresIn)

    private fun tokenReply(access: String, refresh: String) = HardcoverHttpReply(
        200, """{"access_token":"$access","refresh_token":"$refresh","scope":"read:me","expires_in":604800}"""
    )

    private val refreshes get() = sent.filter { it.form["grant_type"] == "refresh_token" }

    @Test fun `not signed in gives no token`() = runBlocking {
        assertNull(session(MemoryStore(null)).accessToken())
    }

    @Test fun `a token with time left is used as is`() = runBlocking {
        assertEquals("hc_at_old", session(MemoryStore(tokens())).accessToken())
        assertTrue(sent.isEmpty())
    }

    @Test fun `a token inside the 60 second window is refreshed and the new pair stored first`() = runBlocking {
        val store = MemoryStore(tokens(expiresIn = 30_000))
        reply = { tokenReply("hc_at_new", "hc_rt_new") }

        assertEquals("hc_at_new", session(store).accessToken())
        assertEquals("hc_rt_new", store.tokens?.refreshToken)
        val form = refreshes.single().form
        assertEquals(HardcoverOAuth.TOKEN_URL, refreshes.single().url)
        assertEquals("hc_rt_old", form["refresh_token"])
        assertEquals("client-1", form["client_id"])
    }

    @Test fun `concurrent callers share one refresh, so a refresh token is never sent twice`() = runBlocking {
        val store = MemoryStore(tokens(expiresIn = 30_000))
        val gate = CompletableDeferred<Unit>()
        reply = { gate.await(); tokenReply("hc_at_new", "hc_rt_new") }
        val session = session(store)

        val callers = (1..8).map { async(Dispatchers.Default) { session.accessToken() } }
        withTimeout(5_000) { while (refreshes.isEmpty()) kotlinx.coroutines.delay(5) }
        gate.complete(Unit)

        assertEquals(List(8) { "hc_at_new" }, callers.awaitAll())
        assertEquals(1, refreshes.size)
    }

    @Test fun `a token the API rejected is refreshed once, even with time left`() = runBlocking {
        val store = MemoryStore(tokens())
        reply = { tokenReply("hc_at_new", "hc_rt_new") }
        val session = session(store)

        assertEquals("hc_at_new", session.accessToken(rejected = "hc_at_old"))
        // Already replaced: a second rejection of the old token doesn't refresh again.
        assertEquals("hc_at_new", session.accessToken(rejected = "hc_at_old"))
        assertEquals(1, refreshes.size)
    }

    @Test fun `invalid_grant deletes the tokens`() = runBlocking {
        val store = MemoryStore(tokens(expiresIn = 30_000))
        reply = { HardcoverHttpReply(400, """{"error":"invalid_grant"}""") }

        assertThrows(HardcoverOAuthException.InvalidGrant::class.java) { runBlocking { session(store).accessToken() } }
        assertNull(store.tokens)
    }

    @Test fun `other failures keep an unexpired token and wait 10 s before trying again`() = runBlocking {
        val store = MemoryStore(tokens(expiresIn = 30_000))
        reply = { throw IOException("offline") }
        val session = session(store)

        assertEquals("hc_at_old", session.accessToken())
        assertEquals("hc_at_old", session.accessToken())
        assertEquals(1, refreshes.size)

        now += HardcoverOAuth.REFRESH_RETRY_DELAY_MS
        reply = { tokenReply("hc_at_new", "hc_rt_new") }
        assertEquals("hc_at_new", session.accessToken())
        assertEquals(2, refreshes.size)
        assertEquals("hc_rt_old", refreshes.last().form["refresh_token"])
    }

    @Test fun `a failed refresh of an expired token throws and keeps the sign-in`() = runBlocking {
        val store = MemoryStore(tokens(expiresIn = -1))
        reply = { HardcoverHttpReply(503, "") }

        assertThrows(HardcoverOAuthException.Http::class.java) { runBlocking { session(store).accessToken() } }
        assertEquals("hc_rt_old", store.tokens?.refreshToken)
    }

    @Test fun `a refresh answer for a sign-in that changed meanwhile is dropped`() = runBlocking {
        val store = MemoryStore(tokens(expiresIn = 30_000))
        reply = {
            store.tokens = tokens(access = "hc_at_other", refresh = "hc_rt_other") // signed in again meanwhile
            tokenReply("hc_at_new", "hc_rt_new")
        }

        assertThrows(HardcoverSignInChangedException::class.java) { runBlocking { session(store).accessToken() } }
        assertEquals("hc_rt_other", store.tokens?.refreshToken)
    }

    @Test fun `sign-in exchanges the code with the verifier and redirect`() = runBlocking {
        val store = MemoryStore(null)
        reply = { tokenReply("hc_at_1", "hc_rt_1") }

        session(store).signIn(code = "the-code", codeVerifier = "the-verifier")

        val form = sent.single().form
        assertEquals("authorization_code", form["grant_type"])
        assertEquals("the-code", form["code"])
        assertEquals("the-verifier", form["code_verifier"])
        assertEquals(HardcoverOAuth.REDIRECT_URI, form["redirect_uri"])
        assertEquals("client-1", form["client_id"])
        assertEquals("hc_at_1", store.tokens?.accessToken)
    }

    @Test fun `sign-out deletes the tokens, then revokes the refresh token`() = runBlocking {
        val store = MemoryStore(tokens())
        var storedAtRevoke: HardcoverTokenSet? = tokens()
        reply = { storedAtRevoke = store.tokens; HardcoverHttpReply(200, "") }

        session(store).signOut()

        assertNull(store.tokens)
        assertNull(storedAtRevoke)
        assertEquals(HardcoverOAuth.REVOKE_URL, sent.single().url)
        assertEquals("hc_rt_old", sent.single().form["token"])
        assertEquals("client-1", sent.single().form["client_id"])
    }

    @Test fun `sign-out ignores a failed revoke`() = runBlocking {
        val store = MemoryStore(tokens(refresh = null))
        reply = { throw IOException("offline") }

        session(store).signOut()

        assertNull(store.tokens)
        assertEquals("hc_at_old", sent.single().form["token"])
    }
}
