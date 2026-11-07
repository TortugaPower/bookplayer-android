package com.tortugapower.audiobookplayer.logic

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.core.CoreContext
import com.tortugapower.audiobookplayer.network.HardcoverOAuth
import com.tortugapower.audiobookplayer.network.HardcoverService
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
        HardcoverAccount.sealPending = defaultSeal
        HardcoverAccount.openPending = defaultOpen
        HardcoverAccount.readMe = defaultReadMe
    }

    private val defaultReadMe = HardcoverAccount.readMe
    private val defaultSeal = HardcoverAccount.sealPending
    private val defaultOpen = HardcoverAccount.openPending

    /** Robolectric has no Keystore: the saved attempt is "sealed" by reversing it, so it's never stored as is. */
    private fun plainPendingCipher() {
        HardcoverAccount.sealPending = { it.reversed() }
        HardcoverAccount.openPending = { it.reversed() }
    }

    private suspend fun beginInBrowser(now: Long = 1_000L): String {
        plainPendingCipher()
        val url = HardcoverAccount.beginBrowserSignIn(now)
        return HardcoverOAuth.queryParameters(url).getValue("state")
    }

    private fun redirect(state: String, extra: String = "code=abc") = "${HardcoverOAuth.REDIRECT_URI}?$extra&state=$state"

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
        HardcoverAccount.disconnect()
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

    @Test fun `an expired sign-in whose refresh fails offline throws, rather than reading as not connected`() = runBlocking {
        store.tokens = tokens(expiresAt = System.currentTimeMillis() - 1)
        send = { _, _ -> throw IOException("offline") }

        val failure = runCatching { HardcoverAccount.credential() }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals("the sign-in is kept for the next try", "hc_at", store.tokens?.accessToken)
    }

    @Test fun `a refused me throws its status for progress, and reads as no id elsewhere`() = runBlocking {
        HardcoverSettingsManager.setToken(context, "hc_revoked")
        HardcoverAccount.readMe = { throw HardcoverService.HardcoverRequestException(401, "HTTP 401") }

        val failure = runCatching { HardcoverAccount.connectedUserIdOrThrow() }.exceptionOrNull()

        assertEquals(401, (failure as HardcoverService.HardcoverRequestException).status)
        assertNull(HardcoverAccount.fetchConnectedUserId())
    }

    @Test fun `a regular browser's return signs in with the saved attempt, once`() = runBlocking {
        HardcoverSettingsManager.setToken(context, "hc_key")
        val state = beginInBrowser()
        var form: Map<String, String>? = null
        val answer = send
        send = { url, f -> if (url == HardcoverOAuth.TOKEN_URL) form = f; answer(url, f) }

        assertTrue(HardcoverAccount.completeBrowserSignIn(redirect(state), now = 2_000L))
        assertEquals("hc_at_new", store.tokens?.accessToken)
        assertEquals("abc", form?.get("code"))
        assertTrue("the saved verifier is sent", form?.get("code_verifier").orEmpty().isNotEmpty())
        assertEquals("the sign-in replaces the API key", "", storedKey())

        // Delivered again (reopened from recents): the attempt is used up
        store.tokens = null
        assertFalse(HardcoverAccount.completeBrowserSignIn(redirect(state), now = 3_000L))
        assertNull(store.tokens)
    }

    @Test fun `a return that isn't the saved attempt's is ignored, and the attempt keeps waiting`() = runBlocking {
        val state = beginInBrowser()

        assertFalse(HardcoverAccount.completeBrowserSignIn(redirect("forged"), now = 2_000L))
        assertFalse(HardcoverAccount.completeBrowserSignIn(HardcoverOAuth.REDIRECT_URI + "?code=abc", now = 2_000L))
        assertNull(store.tokens)

        assertTrue(HardcoverAccount.completeBrowserSignIn(redirect(state), now = 3_000L))
    }

    @Test fun `nothing started, nothing signs in`() = runBlocking {
        plainPendingCipher()
        assertFalse(HardcoverAccount.completeBrowserSignIn(redirect("any"), now = 2_000L))
        assertNull(store.tokens)
    }

    @Test fun `an attempt older than ten minutes is dropped`() = runBlocking {
        val state = beginInBrowser(now = 1_000L)

        assertFalse(HardcoverAccount.completeBrowserSignIn(redirect(state), now = 1_000L + HardcoverAccount.PENDING_SIGN_IN_TTL_MS + 1))
        assertNull(store.tokens)
    }

    @Test fun `a refused authorization uses up the attempt and reports it`() = runBlocking {
        val state = beginInBrowser()

        val failure = runCatching {
            HardcoverAccount.completeBrowserSignIn(redirect(state, extra = "error=access_denied"), now = 2_000L)
        }.exceptionOrNull()

        assertTrue(failure is com.tortugapower.audiobookplayer.network.HardcoverOAuthException.Server)
        assertFalse(HardcoverAccount.completeBrowserSignIn(redirect(state), now = 3_000L))
        assertNull(store.tokens)
    }

    @Test fun `unlinking drops a browser sign-in still waiting`() = runBlocking {
        val state = beginInBrowser()

        HardcoverAccount.disconnect()

        assertFalse(HardcoverAccount.completeBrowserSignIn(redirect(state), now = 2_000L))
        assertNull(store.tokens)
    }

    @Test fun `the saved attempt is sealed`() = runBlocking {
        val state = beginInBrowser()
        val stored = context.dataStore.data.first()[stringPreferencesKey("hardcover_pending_sign_in")].orEmpty()
        assertFalse(stored.contains(state))
    }

    @Test fun `unlinking drops the progress still queued for the account, and what was sent`() = runBlocking {
        val db = com.tortugapower.audiobookplayer.database.AppDatabase.getDatabase(context)
        kotlinx.coroutines.withContext(Dispatchers.IO) { db.clearAllTables() }
        val dao = db.syncTaskDao()
        val tasks = com.tortugapower.audiobookplayer.repository.RoomSyncTaskRepository(dao)
        SyncTaskFactory.createHardcoverProgressTask(tasks, "book-1", 40.0)
        SyncTaskFactory.createHardcoverUpdateStatusTask(tasks, "book-2", 2)
        HardcoverProgressSync.changes.isNew("book-1", 40.0)

        HardcoverAccount.disconnect()

        val left = tasks.getPendingTasks().map { it.jobType }
        assertEquals(listOf(SyncTaskFactory.JOB_HARDCOVER_UPDATE_STATUS), left)
        assertTrue("the next account gets it again", HardcoverProgressSync.changes.isNew("book-1", 40.0))
    }
}
