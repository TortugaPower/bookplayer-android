package com.tortugapower.audiobookplayer.logic

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Asks for the notification permission (Android 13+) the first time a transfer runs with the app on screen
 * ([SyncKeepAlive.transferOnScreen]): a download the user started, or an upload the app queued itself (an imported
 * book's). Without the permission the sync service's notice ("Downloading books") stays hidden once the app leaves.
 * The service runs either way, and playback's notification needs no permission. Asked once: a denial stands, and the
 * system's app settings can still turn notifications on.
 */
object NotificationPermissionPrompt {
    internal val ASKED = booleanPreferencesKey("notification_permission_asked")

    /** Whether to ask, on Android [sdkInt] */
    internal fun shouldAsk(sdkInt: Int, granted: Boolean, askedBefore: Boolean): Boolean =
        sdkInt >= Build.VERSION_CODES.TIRAMISU && !granted && !askedBefore

    /** Asks through [request] (the activity's permission launcher), unless it isn't needed or was asked before */
    suspend fun maybeAsk(context: Context, sdkInt: Int = Build.VERSION.SDK_INT, request: () -> Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val asked = context.dataStore.data.map { it[ASKED] ?: false }.first()
        if (!shouldAsk(sdkInt, granted, asked)) return
        context.dataStore.edit { it[ASKED] = true }
        request()
    }
}
