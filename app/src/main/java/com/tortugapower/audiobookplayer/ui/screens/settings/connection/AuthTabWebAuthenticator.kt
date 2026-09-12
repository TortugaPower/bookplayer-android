package com.tortugapower.audiobookplayer.ui.screens.settings.connection

import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.browser.auth.AuthTabIntent
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsService
import androidx.core.net.toUri
import com.tortugapower.audiobookplayer.network.WebAuthResult
import com.tortugapower.audiobookplayer.network.WebAuthenticator
import kotlinx.coroutines.CompletableDeferred

/**
 * The SSO browser leg over Chrome's Auth Tab — Android's counterpart to `ASWebAuthenticationSession`.
 * The Auth Tab intercepts the redirect to our callback scheme inside the browser and returns it as an
 * activity result, so no intent-filter is declared, nothing else on the device can claim the callback,
 * and AudiobookShelf keeps its default redirect URI.
 *
 * The launcher comes from `rememberLauncherForActivityResult` in the sheet that owns this object;
 * [deliver] is the launcher's callback. One handshake at a time: a second [authenticate] while one is
 * pending fails immediately rather than replacing the deferred the first one is awaiting.
 */
class AuthTabWebAuthenticator(
    private val context: Context,
    /** The Custom Tabs provider that supports Auth Tab, from [SsoAvailability.authTabProvider]. */
    private val provider: String,
) : WebAuthenticator {
    var launcher: ActivityResultLauncher<Intent>? = null

    private var pending: CompletableDeferred<WebAuthResult>? = null

    override suspend fun authenticate(url: String, callbackScheme: String, ephemeral: Boolean): WebAuthResult {
        val launcher = launcher ?: return WebAuthResult.Failed(NO_LAUNCHER)
        if (pending != null) return WebAuthResult.Failed(CONCURRENT)
        val deferred = CompletableDeferred<WebAuthResult>()
        pending = deferred
        try {
            // Ephemeral browsing keeps a live identity-provider cookie from signing the existing account
            // straight back in when adding a second one. Degrade to a normal tab when the provider can't.
            val useEphemeral = ephemeral && CustomTabsClient.isEphemeralBrowsingSupported(context, provider)
            val authTab = AuthTabIntent.Builder().setEphemeralBrowsingEnabled(useEphemeral).build()
            // Pin the provider we verified supports Auth Tab; the default handler may be a browser that doesn't.
            authTab.intent.setPackage(provider)
            authTab.launch(launcher, url.toUri(), callbackScheme)
            return deferred.await()
        } finally {
            if (pending === deferred) pending = null
        }
    }

    /** The launcher's callback. A result with no handshake waiting (the sheet was left) is dropped. */
    fun deliver(result: AuthTabIntent.AuthResult) {
        pending?.complete(mapResult(result.resultCode, result.resultUri?.toString()))
    }

    companion object {
        /** No launcher attached yet — the sheet hasn't composed. */
        const val NO_LAUNCHER = -100
        /** A handshake is already pending. */
        const val CONCURRENT = -101

        /** Maps the Auth Tab's result to the flow's vocabulary. Pure, so it's unit-tested without an Activity. */
        fun mapResult(resultCode: Int, resultUri: String?): WebAuthResult = when {
            resultCode == AuthTabIntent.RESULT_OK && resultUri != null -> WebAuthResult.Callback(resultUri)
            resultCode == AuthTabIntent.RESULT_CANCELED -> WebAuthResult.Cancelled
            else -> WebAuthResult.Failed(resultCode)
        }
    }
}

/** Which browser, if any, can run the SSO leg on this device. */
object SsoAvailability {
    /**
     * The Custom Tabs provider to use for Auth Tab: the user's default provider when it declares Auth Tab
     * support, otherwise any installed provider that does. Capability-based (the `AUTH_TAB` category on
     * the browser's Custom Tabs service), not package-based — today that is Chrome 137+, and any browser
     * that ships the feature later is picked up without a change here. Null means SSO is not offered on
     * this device; there is no fallback path.
     */
    fun authTabProvider(context: Context): String? {
        val default = CustomTabsClient.getPackageName(context, null)
        if (default != null && CustomTabsClient.isAuthTabSupported(context, default)) return default
        val candidates = context.packageManager
            .queryIntentServices(Intent(CustomTabsService.ACTION_CUSTOM_TABS_CONNECTION), 0)
            .mapNotNull { it.serviceInfo?.packageName }
            .distinct()
        return candidates.firstOrNull { CustomTabsClient.isAuthTabSupported(context, it) }
    }
}
