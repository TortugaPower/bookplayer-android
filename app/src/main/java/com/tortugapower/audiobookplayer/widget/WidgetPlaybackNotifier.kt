package com.tortugapower.audiobookplayer.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.annotation.VisibleForTesting
import com.tortugapower.audiobookplayer.core.CoreContext
import com.tortugapower.audiobookplayer.logic.StorageMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 * Both paths stay in-process. The rebuild ([rebuild], shared with theme changes) calls
 * [AudioWidgetLargeRenderer.refresh] rather than broadcasting APPWIDGET_UPDATE at our own receiver — see the
 * renderer for the vivo launch crash that ruled that out.
 */
object WidgetPlaybackNotifier {
    private val scope = CoroutineScope(
        Dispatchers.Main + SupervisorJob() + StorageMonitor.exceptionHandler { CoreContext.appContextOrNull }
    )

    /** The rebuild, swappable so a test can check the routing without Room or Coil. */
    @VisibleForTesting
    internal var refresh: suspend (Context, IntArray) -> Unit = AudioWidgetLargeRenderer::refresh

    /** The in-flight rebuild; a newer one cancels it so an older rebuild can never land last. */
    private var refreshJob: Job? = null

    /**
     * Rebuilds every placed widget in-process — the one entry for playback and theme changes, so
     * overlapping rebuilds coalesce instead of racing. Callable from any thread: the job bookkeeping
     * runs on [scope]'s main dispatcher.
     */
    fun rebuild(context: Context) {
        // Hold the application context, never a caller's Activity, for as long as the render runs.
        val appContext = context.applicationContext
        val largeIds = AppWidgetManager.getInstance(appContext).getAppWidgetIds(
            ComponentName(appContext, AudioWidgetLargeProvider::class.java)
        )
        // No widgets placed — skip the rebuild (also covers the combine's cold-start emission).
        if (largeIds.isEmpty()) return
        scope.launch {
            refreshJob?.cancel()
            refreshJob = coroutineContext[Job]
            refresh(appContext, largeIds)
        }
    }

    fun notify(context: Context, itemChanged: Boolean, isPlaying: Boolean) {
        if (itemChanged) {
            rebuild(context)
        } else {
            AudioWidgetLargeProvider.pushPlayStateUpdate(context, isPlaying)
        }
    }
}
