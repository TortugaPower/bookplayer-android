package com.tortugapower.audiobookplayer.network

import android.os.Build

/**
 * How this installation introduces itself to media servers — the `MediaBrowser` authorization header
 * Jellyfin requires on every call (`Client`, `Device`, `Version`), which is also what its Quick Connect
 * approval screen and Devices dashboard display. Injected by the host at startup like
 * [NetworkConstants.configure]: a shared library module can't read the app's `BuildConfig`, and
 * `1.0.0` hardcoded in the service is what every Android install used to report.
 */
object ClientIdentity {
    var appName: String = "BookPlayer"
        private set
    var appVersion: String = "0"
        private set
    var deviceName: String = defaultDeviceName()
        private set

    fun configure(appName: String, appVersion: String, deviceName: String = defaultDeviceName()) {
        this.appName = headerSafe(appName).ifEmpty { "BookPlayer" }
        this.appVersion = headerSafe(appVersion).ifEmpty { "0" }
        this.deviceName = headerSafe(deviceName).ifEmpty { "Android" }
    }

    /** `Build.MODEL` is a platform type that is null under plain JVM unit tests; never let that surface as an NPE. */
    private fun defaultDeviceName(): String = (Build.MODEL as String?) ?: "Android"

    /** Header values are quoted inside the MediaBrowser scheme, so quotes and non-printable/non-ASCII bytes must not survive. */
    private fun headerSafe(value: String): String =
        value.filter { it.code in 0x20..0x7e && it != '"' && it != '\\' }.trim()
}
