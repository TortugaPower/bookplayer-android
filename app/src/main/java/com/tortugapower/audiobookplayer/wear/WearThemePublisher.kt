package com.tortugapower.audiobookplayer.wear

import android.content.Context
import android.util.Log
import androidx.compose.runtime.snapshotFlow
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.tortugapower.audiobookplayer.datalayer.WatchRemoteCodec
import com.tortugapower.audiobookplayer.datalayer.WatchTheme
import com.tortugapower.audiobookplayer.datalayer.WearDataLayer
import com.tortugapower.audiobookplayer.logic.ThemeManager
import com.tortugapower.audiobookplayer.ui.theme.BookPlayerThemeSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Mirrors the user's selected app theme to the watch over the Wear Data Layer ([WearDataLayer.PATH_THEME]),
 * so the watch UI matches. Theme choice is device-local on the phone (never backend-synced), so this is the
 * only way the watch can learn it. Sends the theme's **dark-variant** colors (the watch is dark-first);
 * observes [ThemeManager.currentTheme] reactively via `snapshotFlow`, so a re-theme re-publishes on its own.
 * DataClient keeps the latest item, so the watch restores the theme on reconnect without a re-send.
 */
object WearThemePublisher {
    private const val TAG = "WearThemePublisher"

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dataClient by lazy { Wearable.getDataClient(appContext) }

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun start() {
        scope.launch {
            snapshotFlow { ThemeManager.currentTheme }
                .distinctUntilChanged()
                .collect { publish(it) }
        }
    }

    private fun publish(spec: BookPlayerThemeSpec) {
        val theme = WatchTheme(
            accentHex = spec.darkAccentHex,
            primaryHex = spec.darkPrimaryHex,
            secondaryHex = spec.darkSecondaryHex,
            backgroundHex = spec.darkSystemBackgroundHex,
            surfaceHex = spec.darkSecondarySystemBackgroundHex,
            separatorHex = spec.darkSeparatorHex,
        )
        val request = PutDataMapRequest.create(WearDataLayer.PATH_THEME).apply {
            dataMap.putByteArray(WearDataLayer.KEY_PAYLOAD, WatchRemoteCodec.encodeTheme(theme))
        }.asPutDataRequest().setUrgent()
        try {
            Tasks.await(dataClient.putDataItem(request))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to publish theme to watch", e)
        }
    }
}
