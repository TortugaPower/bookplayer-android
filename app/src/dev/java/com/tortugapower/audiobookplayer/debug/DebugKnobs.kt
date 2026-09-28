package com.tortugapower.audiobookplayer.debug

import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * Dev flavor only: knobs `scripts/chaos` set with `adb shell settings put global …`. Absent → production
 * behaviour. The `prod` source set compiles the same object with every knob returning null.
 */
object DebugKnobs {
    /**
     * media3's foreground-service timeout after a pause (production: 10 minutes), so a rig can reach the
     * demoted state in seconds: `adb shell settings put global bookplayer_media_fgs_timeout_ms 15000`.
     */
    fun mediaForegroundTimeoutMs(context: Context): Long? =
        Settings.Global.getString(context.contentResolver, "bookplayer_media_fgs_timeout_ms")?.toLongOrNull()
            .also { Log.i(TAG, "media foreground timeout override: ${it ?: "none"}") }

    private const val TAG = "DebugKnobs"
}
