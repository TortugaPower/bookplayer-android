package com.tortugapower.audiobookplayer.logic

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Asks for the notification permission (Android 13+) the first time a transfer runs with the app on screen
 * ([SyncKeepAlive.transferOnScreen]): a download the user started, or an upload the app queued itself (an imported
 * book's). Without the permission the sync service's notice ("Downloading books") stays hidden once the app leaves.
 * The service runs either way, and playback's notification needs no permission. Asked once: a denial stands, and the
 * system's app settings can still turn notifications on.
 */
object NotificationPermissionPrompt {
    // In the app's shared key-value store (Context.dataStore), as the Hardcover settings are
    internal val ASKED = booleanPreferencesKey("notification_permission_asked")

    /** Whether to ask, on Android [sdkInt] */
    internal fun shouldAsk(sdkInt: Int, granted: Boolean, askedBefore: Boolean): Boolean =
        sdkInt >= Build.VERSION_CODES.TIRAMISU && !granted && !askedBefore

    /**
     * Asks through [request] (the activity's permission launcher), unless it isn't needed or was asked before. A full
     * disk (a download may just have filled it) is reported and skips the ask; a later transfer asks again.
     */
    suspend fun maybeAsk(context: Context, sdkInt: Int = Build.VERSION.SDK_INT, request: () -> Unit) =
        maybeAsk(context, sdkInt, context.dataStore, { StorageMonitor.reportFailure(context, it) }, request)

    /** [reportedFullDisk] reports a failed read or write of [store] if it's a full disk, and says whether it was */
    internal suspend fun maybeAsk(
        context: Context,
        sdkInt: Int,
        store: DataStore<Preferences>,
        reportedFullDisk: (IOException) -> Boolean,
        request: () -> Unit,
    ) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        try {
            val asked = store.data.map { it[ASKED] ?: false }.first()
            if (!shouldAsk(sdkInt, granted, asked)) return
            store.edit { it[ASKED] = true }
        } catch (e: IOException) {
            // Anything but a full disk is a bug, and crashes
            if (reportedFullDisk(e)) return
            throw e
        }
        request()
    }
}
