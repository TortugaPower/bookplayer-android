package com.tortugapower.audiobookplayer.logic

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.tortugapower.audiobookplayer.core.CoreContext
import com.tortugapower.audiobookplayer.network.HardcoverOAuth
import com.tortugapower.audiobookplayer.network.HardcoverOAuthException
import com.tortugapower.audiobookplayer.network.HardcoverService
import com.tortugapower.audiobookplayer.network.HardcoverTokenSet
import com.tortugapower.audiobookplayer.network.HardcoverUser
import com.tortugapower.audiobookplayer.network.WebAuthResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** What the Hardcover settings screen shows. */
data class HardcoverConnection(
    /** Signed in with OAuth, not an API key. */
    val isSignedInWithOAuth: Boolean = false,
    val hasApiKey: Boolean = false,
    /** The connected account's username, once read from `me`. */
    val username: String? = null,
    /** Only signing in again helps: a scope the app now asks for is missing, or [signInRefused]. */
    val needsSignInAgain: Boolean = false,
    /** Hardcover refused to refresh the sign-in for good: it stops working once its token expires. */
    val signInRefused: Boolean = false,
) {
    val isConnected: Boolean get() = isSignedInWithOAuth || hasApiKey
}

/** The token a request sends, and whether it's an OAuth one (only those are refreshed after a 401). */
data class HardcoverCredential(val token: String, val isOAuth: Boolean)

/**
 * The connected Hardcover account (iOS `HardcoverService`'s credential half): one at a time, by OAuth sign-in or a
 * pasted API key; a sign-in replaces the key, and OAuth wins when both exist. The OAuth tokens are one value in the
 * settings DataStore, encrypted with the Keystore key [CredentialCipher] holds (iOS: Keychain `hardcover_session`).
 */
object HardcoverAccount {
    private const val TAG = "HardcoverAccount"

    private val SESSION = stringPreferencesKey("hardcover_session")
    private val USER_ID = stringPreferencesKey("hardcover_user_id")
    private val USERNAME = stringPreferencesKey("hardcover_username")

    private val gson = Gson()

    private val context: Context get() = CoreContext.appContext

    private val oauthHttp by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val store = object : HardcoverTokenStore {
        // Decrypting is a Keystore call: never on the caller's (often Main) thread.
        override suspend fun read(): HardcoverTokenSet? = withContext(Dispatchers.Default) {
            decodeSession(context.dataStore.data.first())
        }
        override suspend fun write(tokens: HardcoverTokenSet) {
            // Encrypting is a Keystore call too (and generates the key the first time): never on Main, which sign-in is.
            val encrypted = withContext(Dispatchers.Default) { CredentialCipher.encrypt(gson.toJson(tokens)) }
            context.dataStore.edit { it[SESSION] = encrypted }
        }
        override suspend fun clear() {
            context.dataStore.edit { it.remove(SESSION) }
        }
    }

    private val defaultSession: HardcoverOAuthSession by lazy {
        HardcoverOAuthSession(store = store, send = ::postForm)
    }

    /** Tests swap in a session with an in-memory store: the real one needs the Keystore, which Robolectric lacks. */
    @VisibleForTesting
    internal var sessionOverride: HardcoverOAuthSession? = null

    val session: HardcoverOAuthSession get() = sessionOverride ?: defaultSession

    /** Reads the connected account from `me`; tests answer it themselves. */
    @VisibleForTesting
    internal var readMe: suspend () -> HardcoverUser? = { HardcoverService.meOrThrow() }

    /**
     * Bumped whenever the connected account may have changed. A `me` answer is stored only if none happened while it
     * was on its way: one sent with the previous account's credential would otherwise stay cached as this one's.
     */
    private val accountGeneration = AtomicLong()

    private suspend fun postForm(url: String, form: Map<String, String>): HardcoverHttpReply = withContext(Dispatchers.IO) {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val request = Request.Builder().url(url).post(body).header("Accept", "application/json").build()
        oauthHttp.newCall(request).execute().use { HardcoverHttpReply(it.code, it.body?.string().orEmpty()) }
    }

    private fun decodeSession(prefs: Preferences): HardcoverTokenSet? {
        val stored = prefs[SESSION] ?: return null
        val json = CredentialCipher.decrypt(stored).takeIf { it.isNotEmpty() } ?: return null
        return runCatching { gson.fromJson(json, HardcoverTokenSet::class.java) }.getOrNull()
            ?.takeIf { @Suppress("SENSELESS_COMPARISON") (it.accessToken != null) && it.accessToken.isNotEmpty() }
    }

    /** The settings screen's state, live. */
    fun connection(context: Context): Flow<HardcoverConnection> = context.dataStore.data.map { prefs ->
        val tokens = decodeSession(prefs)
        HardcoverConnection(
            isSignedInWithOAuth = tokens != null,
            hasApiKey = apiKey(prefs[HardcoverSettingsManager.HARDCOVER_TOKEN].orEmpty()).isNotEmpty(),
            username = prefs[USERNAME],
            needsSignInAgain = tokens?.needsSignInAgain == true,
            signInRefused = tokens?.refreshRefused == true,
        )
    }
        // Decrypting the session is a Keystore call + AES/GCM: not on the collector's (usually Main) thread,
        // as RoomAccountRepository and ExternalServerRepository do with their credentials.
        .flowOn(Dispatchers.Default)

    /** Connected either way: Hardcover work runs only then. */
    suspend fun isConnected(context: Context = this.context): Boolean = connection(context).first().isConnected

    /**
     * OAuth first (refreshed when it's about to expire), else the API key. Null when not connected. A refresh that
     * failed for a reason Hardcover may get over (offline, rate limited, down) with the token already expired throws:
     * the caller's request fails as it would have, and a queued task is retried.
     */
    suspend fun credential(): HardcoverCredential? {
        val oauth = oauthToken(rejected = null)
        if (oauth != null) return HardcoverCredential(oauth, isOAuth = true)
        // Cleaned up as it's read: keys saved by older versions can still carry the `Bearer ` prefix they were
        // copied with, and the request adds its own.
        val key = apiKey(HardcoverSettingsManager.getToken(context).first())
        return key.takeIf { it.isNotEmpty() }?.let { HardcoverCredential(it, isOAuth = false) }
    }

    /** A new OAuth token after the API refused [rejected]; null when there's none to try. Throws as [credential]. */
    suspend fun replaceRejected(rejected: String): String? = oauthToken(rejected)

    /**
     * The sign-in's access token, or null without one. An ended sign-in (`invalid_grant`) has been deleted: null, and
     * an API key can't be there too, it was replaced. A sign-in replaced while refreshing hands over the current one's.
     */
    private suspend fun oauthToken(rejected: String?): String? = try {
        session.accessToken(rejected)
    } catch (e: HardcoverOAuthException.InvalidGrant) {
        Log.w(TAG, "The Hardcover sign-in has ended")
        null
    } catch (e: HardcoverSignInChangedException) {
        // Signed out or in again during the refresh: the token refused belonged to the previous sign-in
        if (rejected == null) session.accessToken() else null
    }

    /**
     * Signs in through Hardcover's authorize page, replacing an API key.
     * @param authenticate opens the page and returns how the browser came back.
     * @return false when the user closed the browser (not an error); throws on any other failure.
     */
    suspend fun signIn(authenticate: suspend (url: String) -> WebAuthResult): Boolean {
        val attempt = HardcoverOAuth.makeAuthorization()
        val redirect = when (val result = authenticate(attempt.url)) {
            is WebAuthResult.Callback -> result.uri
            WebAuthResult.Cancelled -> return false
            is WebAuthResult.Failed -> throw IllegalStateException("Browser failed: ${result.code}")
        }
        val code = HardcoverOAuth.authorizationCode(redirect, attempt)
        session.signIn(code, attempt.codeVerifier)
        clearConnectedUser()
        // One account at a time: the sign-in replaces an API key.
        HardcoverSettingsManager.setToken(context, "")
        Log.i(TAG, "Signed in to Hardcover")
        fetchConnectedUserId()
        return true
    }

    /** Disconnect: revoke a sign-in and forget the API key and the cached account. */
    suspend fun disconnect() {
        // Finished even if the caller is cancelled (Unlink then Save leaves the screen, clearing its ViewModel):
        // stopped halfway, the key would be gone but the sign-in still stored, and the app would still show connected.
        withContext(NonCancellable) {
            HardcoverSettingsManager.setToken(context, "")
            session.signOut()
            clearConnectedUser()
        }
        Log.i(TAG, "Disconnected from Hardcover")
    }

    /**
     * Saves a pasted API key: trimmed and without a leading `Bearer` (older keys were copied with it). The same key
     * written back by Save isn't another account, so nothing is cleared then.
     */
    suspend fun saveApiKey(raw: String) {
        val key = apiKey(raw)
        if (key == apiKey(HardcoverSettingsManager.getToken(context).first())) return
        // The key first: a `me` sent after the clear then can't still be using the old one.
        HardcoverSettingsManager.setToken(context, key)
        clearConnectedUser()
        // The account's id is read when first needed (a new link, a progress update), not here: Save leaves the screen.
    }

    /** The API key in what the user entered. */
    fun apiKey(raw: String): String = raw.trim().replaceFirst(Regex("^(?i)bearer(\\s+|$)"), "").trim()

    suspend fun connectedUserId(): String? = context.dataStore.data.first()[USER_ID]

    /**
     * The connected account's user id, read from `me` the first time it's needed: after signing in, after an API key
     * is saved, or for a connection made before ids were stored. Null when offline. Written as `hostId` on the
     * Hardcover links it makes, so a later version can tell accounts apart.
     */
    suspend fun fetchConnectedUserId(): String? = try {
        connectedUserIdOrThrow()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't read the connected Hardcover account: ${e.javaClass.simpleName}")
        null
    }

    /**
     * As [fetchConnectedUserId], but a failure to read `me` throws (HardcoverService.meOrThrow): a refused key apart
     * from being offline. Null when not connected, or `me` held no user.
     */
    suspend fun connectedUserIdOrThrow(): String? {
        connectedUserId()?.let { return it }
        val generation = accountGeneration.get()
        if (!isConnected()) return null
        val user = readMe() ?: return null
        var stored = false
        context.dataStore.edit { prefs ->
            if (accountGeneration.get() != generation) return@edit
            prefs[USER_ID] = user.id.toString()
            if (user.username != null) prefs[USERNAME] = user.username else prefs.remove(USERNAME)
            stored = true
        }
        // The account changed meanwhile: this answer may be the previous one's. The next caller reads it again.
        return if (stored) user.id.toString() else null
    }

    private suspend fun clearConnectedUser() {
        accountGeneration.incrementAndGet()
        context.dataStore.edit {
            it.remove(USER_ID)
            it.remove(USERNAME)
        }
    }

    /** The sign-in lacks [scope] (one added after it was granted). */
    suspend fun lacksScope(scope: String): Boolean = session.tokens()?.missingScopes?.contains(scope) == true
}
