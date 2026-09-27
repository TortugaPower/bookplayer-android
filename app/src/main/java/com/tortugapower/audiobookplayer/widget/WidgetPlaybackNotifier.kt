package com.tortugapower.audiobookplayer.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.annotation.VisibleForTesting
import com.tortugapower.audiobookplayer.core.CoreContext
import com.tortugapower.audiobookplayer.logic.StorageMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Phone-side hook that keeps the home-screen widget in sync with playback. Wired into `PlaybackManager`'s
 * `onPlaybackStateChanged` callback (so `PlaybackManager` itself carries no widget dependency and can live in
 * `:core`).
 *
 * iOS-style split (WidgetReloadService): a book change is a rare transition that rebuilds the whole widget;
 * a play/pause flip only patches the button in place — no DB query, artwork reload, or full RemoteViews
 * payload per tap.
 *
 * Both paths stay in-process. The rebuild calls [AudioWidgetLargeRenderer.refresh] rather than broadcasting
 * APPWIDGET_UPDATE at our own receiver — see the renderer for the vivo launch crash that ruled that out.
 */
object WidgetPlaybackNotifier {
    private val scope = CoroutineScope(
        Dispatchers.Main + SupervisorJob() + StorageMonitor.exceptionHandler { CoreContext.appContextOrNull }
    )

    /** The rebuild, swappable so a test can check the routing without Room or Coil. */
    @VisibleForTesting
    internal var refresh: suspend (Context, IntArray) -> Unit = AudioWidgetLargeRenderer::refresh

    fun notify(context: Context, itemChanged: Boolean, isPlaying: Boolean) {
        if (itemChanged) {
            val largeIds = AppWidgetManager.getInstance(context).getAppWidgetIds(
                ComponentName(context, AudioWidgetLargeProvider::class.java)
            )
            // No widgets placed — skip the rebuild (also covers the combine's cold-start emission).
            if (largeIds.isNotEmpty()) {
                scope.launch { refresh(context, largeIds) }
            }
        } else {
            AudioWidgetLargeProvider.pushPlayStateUpdate(context, isPlaying)
        }
    }
}
