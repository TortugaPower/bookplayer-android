package com.tortugapower.audiobookplayer.ui

import android.content.ActivityNotFoundException
import androidx.compose.ui.platform.UriHandler

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

    /** Thrown bare by `startActivity`, or wrapped by the platform handler as `IllegalArgumentException("Can't open …")`. */
    private fun RuntimeException.isNoHandler(): Boolean =
        this is ActivityNotFoundException || (this is IllegalArgumentException && cause is ActivityNotFoundException)
}
