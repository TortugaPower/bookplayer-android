package com.tortugapower.audiobookplayer.logic

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.tortugapower.audiobookplayer.core.CoreContext
import com.tortugapower.audiobookplayer.network.HardcoverOAuth
import com.tortugapower.audiobookplayer.network.HardcoverService
import com.tortugapower.audiobookplayer.network.HardcoverTokenSet
import com.tortugapower.audiobookplayer.network.WebAuthResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** What the Hardcover settings screen shows. */
data class HardcoverConnection(
    /** Signed in with OAuth, not an API key. */
    val isSignedInWithOAuth: Boolean = false,
    val hasApiKey: Boolean = false,
    /** The connected account's username, once read from `me`. */
    val username: String? = null,
    /** Signed in without every scope the app now asks for: signing in again grants the rest. */
    val needsSignInAgain: Boolean = false,
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
        override suspend fun read(): HardcoverTokenSet? = decodeSession(context.dataStore.data.first())
        override suspend fun write(tokens: HardcoverTokenSet) {
            val encrypted = CredentialCipher.encrypt(gson.toJson(tokens))
            context.dataStore.edit { it[SESSION] = encrypted }
        }
        override suspend fun clear() {
            context.dataStore.edit { it.remove(SESSION) }
        }
    }

    val session: HardcoverOAuthSession by lazy {
        HardcoverOAuthSession(store = store, send = ::postForm)
    }

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
            hasApiKey = !prefs[HardcoverSettingsManager.HARDCOVER_TOKEN].isNullOrBlank(),
            username = prefs[USERNAME],
            needsSignInAgain = tokens?.missingScopes?.isNotEmpty() == true,
        )
    }

    /** Connected either way: Hardcover work runs only then. */
    suspend fun isConnected(context: Context = this.context): Boolean = connection(context).first().isConnected

    /** OAuth first (refreshed when it's about to expire), else the API key. Null when not connected. */
    suspend fun credential(): HardcoverCredential? {
        val oauth = try {
            session.accessToken()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "No usable Hardcover sign-in token: ${e.javaClass.simpleName}")
            // An ended sign-in (invalid_grant) has been deleted: an API key can't be there too, it was replaced.
            null
        }
        if (oauth != null) return HardcoverCredential(oauth, isOAuth = true)
        val key = HardcoverSettingsManager.getToken(context).first()
        return key.takeIf { it.isNotBlank() }?.let { HardcoverCredential(it, isOAuth = false) }
    }

    /** A new OAuth token after the API refused [rejected]; null when there's none to try. */
    suspend fun replaceRejected(rejected: String): String? = try {
        session.accessToken(rejected = rejected)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Hardcover token refresh after a 401 failed: ${e.javaClass.simpleName}")
        null
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
        HardcoverSettingsManager.setToken(context, "")
        session.signOut()
        clearConnectedUser()
        Log.i(TAG, "Disconnected from Hardcover")
    }

    /**
     * Saves a pasted API key: trimmed and without a leading `Bearer` (older keys were copied with it). The same key
     * written back by Save isn't another account, so nothing is cleared then.
     */
    suspend fun saveApiKey(raw: String) {
        val key = apiKey(raw)
        if (key == HardcoverSettingsManager.getToken(context).first()) return
        clearConnectedUser()
        HardcoverSettingsManager.setToken(context, key)
        // The account's id is read when first needed (a new link, a progress update), not here: Save leaves the screen.
    }

    /** The API key in what the user entered. */
    fun apiKey(raw: String): String = raw.trim().replaceFirst(Regex("^(?i)bearer\\s+"), "").trim()

    suspend fun connectedUserId(): String? = context.dataStore.data.first()[USER_ID]

    /**
     * The connected account's user id, read from `me` the first time it's needed: after signing in, after an API key
     * is saved, or for a connection made before ids were stored. Null when offline. Written as `hostId` on the
     * Hardcover links it makes, so a later version can tell accounts apart.
     */
    suspend fun fetchConnectedUserId(): String? {
        connectedUserId()?.let { return it }
        if (!isConnected()) return null
        val user = HardcoverService.me() ?: return null
        context.dataStore.edit { prefs ->
            prefs[USER_ID] = user.id.toString()
            if (user.username != null) prefs[USERNAME] = user.username else prefs.remove(USERNAME)
        }
        return user.id.toString()
    }

    private suspend fun clearConnectedUser() {
        context.dataStore.edit {
            it.remove(USER_ID)
            it.remove(USERNAME)
        }
    }

    /** The sign-in lacks [scope] (one added after it was granted). */
    suspend fun lacksScope(scope: String): Boolean = session.tokens()?.missingScopes?.contains(scope) == true
}
