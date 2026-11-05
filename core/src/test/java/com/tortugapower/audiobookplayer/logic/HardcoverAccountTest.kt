package com.tortugapower.audiobookplayer.logic

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.core.CoreContext
import com.tortugapower.audiobookplayer.network.HardcoverOAuth
import com.tortugapower.audiobookplayer.network.HardcoverTokenSet
import com.tortugapower.audiobookplayer.network.HardcoverUser
import com.tortugapower.audiobookplayer.network.WebAuthResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/** Which Hardcover credential is used, and what a sign-in, a saved key and a disconnect replace or clear. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HardcoverAccountTest {

    private class MemoryStore(var tokens: HardcoverTokenSet?) : HardcoverTokenStore {
        override suspend fun read() = tokens
        override suspend fun write(tokens: HardcoverTokenSet) { this.tokens = tokens }
        override suspend fun clear() { tokens = null }
    }

    private val userId = stringPreferencesKey("hardcover_user_id")
    private lateinit var context: Context
    private val store = MemoryStore(null)
    private val revoked = mutableListOf<Map<String, String>>()
    /** What a revoke saw when it was sent: (tokens still stored, API key still stored). */
    private var stateAtRevoke: Pair<HardcoverTokenSet?, String>? = null
    private var send: suspend (String, Map<String, String>) -> HardcoverHttpReply = { url, form ->
        when (url) {
            HardcoverOAuth.REVOKE_URL -> {
                revoked += form
                stateAtRevoke = store.tokens to HardcoverSettingsManager.getToken(context).first()
                HardcoverHttpReply(200, "{}")
            }
            HardcoverOAuth.TOKEN_URL -> HardcoverHttpReply(
                200, """{"access_token":"hc_at_new","refresh_token":"hc_rt_new","scope":"read:me","expires_in":604800}"""
            )
            else -> throw IOException("unexpected $url")
        }
    }

    private fun tokens(access: String = "hc_at", expiresAt: Long = System.currentTimeMillis() + 86_400_000L) =
        HardcoverTokenSet(access, "hc_rt", "read:catalog read:library write:library read:me", expiresAt)

    @Before fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        CoreContext.init(context)
        context.dataStore.edit { it.clear() }
        HardcoverAccount.sessionOverride = HardcoverOAuthSession(
            store = store,
            send = { url, form -> send(url, form) },
            clientId = { "client-1" },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }

    @After fun tearDown() {
        HardcoverAccount.sessionOverride = null
        HardcoverAccount.readMe = defaultReadMe
    }

    private val defaultReadMe = HardcoverAccount.readMe

    private suspend fun storedKey() = HardcoverSettingsManager.getToken(context).first()
    private suspend fun storedUserId() = context.dataStore.data.first()[userId]

    @Test fun `not connected gives no credential`() = runBlocking {
        assertNull(HardcoverAccount.credential())
    }

    @Test fun `an API key is the credential, without the Bearer prefix older versions stored`() = runBlocking {
        HardcoverSettingsManager.setToken(context, "Bearer hc_key")
        assertEquals(HardcoverCredential("hc_key", isOAuth = false), HardcoverAccount.credential())
    }

    @Test fun `OAuth wins over an API key`() = runBlocking {
        HardcoverSettingsManager.setToken(context, "hc_key")
        store.tokens = tokens()
        assertEquals(HardcoverCredential("hc_at", isOAuth = true), HardcoverAccount.credential())
    }

    @Test fun `an ended sign-in falls back to the API key`() = runBlocking {
        HardcoverSettingsManager.setToken(context, "hc_key")
        store.tokens = tokens(expiresAt = System.currentTimeMillis() - 1)
        send = { _, _ -> HardcoverHttpReply(400, """{"error":"invalid_grant"}""") }

        assertEquals(HardcoverCredential("hc_key", isOAuth = false), HardcoverAccount.credential())
        assertNull(store.tokens)
    }

    @Test fun `saving the same key again, prefixed or not, keeps the account's id`() = runBlocking {
        HardcoverSettingsManager.setToken(context, "hc_key")
        context.dataStore.edit { it[userId] = "42" }

        HardcoverAccount.saveApiKey("  Bearer hc_key ")

        assertEquals("42", storedUserId())
        assertEquals("hc_key", storedKey())
    }

    @Test fun `saving a different key clears the account's id`() = runBlocking {
        HardcoverSettingsManager.setToken(context, "hc_key")
        context.dataStore.edit { it[userId] = "42" }

        HardcoverAccount.saveApiKey("Bearer hc_other")

        assertNull(storedUserId())
        assertEquals("hc_other", storedKey())
    }

    @Test fun `a sign-in replaces the API key and the account's id`() = runBlocking {
        HardcoverSettingsManager.setToken(context, "hc_key")
        context.dataStore.edit { it[userId] = "42" }

        val signedIn = HardcoverAccount.signIn { url ->
            val state = HardcoverOAuth.queryParameters(url)["state"]
            WebAuthResult.Callback("${HardcoverOAuth.REDIRECT_URI}?code=abc&state=$state")
        }

        assertTrue(signedIn)
        assertEquals("hc_at_new", store.tokens?.accessToken)
        assertEquals("", storedKey())
        assertNull(storedUserId())
    }

    @Test fun `closing the browser changes nothing`() = runBlocking {
        HardcoverSettingsManager.setToken(context, "hc_key")

        assertFalse(HardcoverAccount.signIn { WebAuthResult.Cancelled })
        assertEquals("hc_key", storedKey())
        assertNull(store.tokens)
    }

    @Test fun `disconnect forgets everything before it revokes`() = runBlocking {
        HardcoverSettingsManager.setToken(context, "hc_key")
        store.tokens = tokens()
        context.dataStore.edit { it[userId] = "42" }

        HardcoverAccount.disconnect()

        assertEquals("hc_rt", revoked.single()["token"])
        assertEquals(null to "", stateAtRevoke)
        assertNull(store.tokens)
        assertNull(storedUserId())
    }

    @Test fun `the account's id is read from me and kept`() = runBlocking {
        HardcoverSettingsManager.setToken(context, "hc_key")
        HardcoverAccount.readMe = { HardcoverUser(7, "reader") }

        assertEquals("7", HardcoverAccount.fetchConnectedUserId())
        assertEquals("7", storedUserId())
    }

    @Test fun `a me answer that arrives after the account changed isn't kept`() = runBlocking {
        HardcoverSettingsManager.setToken(context, "hc_key")
        val asked = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<HardcoverUser>()
        HardcoverAccount.readMe = { asked.complete(Unit); answer.await() }

        val fetching = async(Dispatchers.Default) { HardcoverAccount.fetchConnectedUserId() }
        asked.await()
        HardcoverAccount.saveApiKey("hc_other")
        answer.complete(HardcoverUser(7, "previous"))

        assertNull(fetching.await())
        assertNull(storedUserId())
    }

    @Test fun `disconnect finishes even when its caller is cancelled`() = runBlocking {
        HardcoverSettingsManager.setToken(context, "hc_key")
        store.tokens = tokens()
        context.dataStore.edit { it[userId] = "42" }
        val revoking = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        send = { _, _ -> revoking.complete(Unit); release.await(); HardcoverHttpReply(200, "{}") }

        val job = launch(Dispatchers.Default) { HardcoverAccount.disconnect() }
        revoking.await()
        job.cancel()
        release.complete(Unit)
        job.join()

        assertNull(store.tokens)
        assertEquals("", storedKey())
        assertNull(storedUserId())
    }
}
