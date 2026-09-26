package com.tortugapower.audiobookplayer.ui

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import com.tortugapower.audiobookplayer.R
import io.sentry.Breadcrumb
import io.sentry.Sentry

/**
 * A [UriHandler] that survives a device with nothing to open the link.
 *
 * Compose's platform handler calls `startActivity(ACTION_VIEW)` and rethrows the resulting
 * [ActivityNotFoundException] as an [IllegalArgumentException] — uncaught, that is a crash on a tap
 * (Sentry ANDROID-BOOKPLAYER-1P/-1R: a Pixel 3 with its browser disabled, tapping a contributor's
 * GitHub link). Every `LocalUriHandler.current.openUri(...)` in the app goes through this instead,
 * installed once in [com.tortugapower.audiobookplayer.ui.theme.BookPlayerTheme], so the call sites
 * stay as they are and none of them can be the one that forgot.
 *
 * Only the missing-handler failure is turned into [onNoHandler]; any other exception still
 * propagates — a wrapper that ate every [IllegalArgumentException] would hide a malformed URI built
 * by our own code.
 */
class SafeUriHandler(
    private val delegate: UriHandler,
    private val onNoHandler: (uri: String) -> Unit,
) : UriHandler {
    override fun openUri(uri: String) {
        try {
            delegate.openUri(uri)
        } catch (e: RuntimeException) {
            if (!e.isNoHandler()) throw e
            onNoHandler(uri)
        }
    }

    /**
     * Thrown bare by `startActivity`, or wrapped by the platform handler as `IllegalArgumentException("Can't open …")`
     * — today one level deep, so the whole causal chain is walked rather than only the immediate cause: a Compose
     * release that adds a wrapper would otherwise bring the crash back with nothing in Sentry to say so. A
     * causeless [IllegalArgumentException] (a malformed URI of our own making) is still not this. Bounded, because a
     * cyclic cause graph is constructible (`initCause`) and this runs on the main thread.
     */
    private fun RuntimeException.isNoHandler(): Boolean =
        generateSequence(this as Throwable) { it.cause }.take(MAX_CAUSE_DEPTH).any { it is ActivityNotFoundException }

    private companion object {
        const val MAX_CAUSE_DEPTH = 8
    }
}

/**
 * The app's [SafeUriHandler] over the platform handler in scope: on a device with nothing to open the link it
 * shows a toast and leaves a Sentry breadcrumb. The scheme alone goes into the breadcrumb — it says how common a
 * no-browser device is without putting a user's link in a report, and a scheme-less URI reads as "unknown"
 * rather than as the whole string.
 */
@Composable
fun rememberSafeUriHandler(): UriHandler {
    val platform = LocalUriHandler.current
    val context = LocalContext.current
    return remember(platform, context) {
        SafeUriHandler(platform) { uri ->
            Sentry.addBreadcrumb(
                Breadcrumb.info("no app to open a ${uri.substringBefore(':', missingDelimiterValue = "unknown")} link")
                    .apply { category = "links" }
            )
            Toast.makeText(context, R.string.common_no_link_handler, Toast.LENGTH_SHORT).show()
        }
    }
}
