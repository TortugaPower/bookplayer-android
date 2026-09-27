package com.tortugapower.audiobookplayer.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.logic.PlaybackManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AudioWidgetLargeProvider : AppWidgetProvider() {

    private val widgetScope = CoroutineScope(Dispatchers.Main + SupervisorJob() + com.tortugapower.audiobookplayer.logic.StorageMonitor.exceptionHandler { com.tortugapower.audiobookplayer.core.CoreContext.appContextOrNull })

    companion object {
        const val ACTION_PLAY_PAUSE = "com.tortugapower.audiobookplayer.widget.large.ACTION_PLAY_PAUSE"
        const val ACTION_REWIND = "com.tortugapower.audiobookplayer.widget.large.ACTION_REWIND"
        const val ACTION_FORWARD = "com.tortugapower.audiobookplayer.widget.large.ACTION_FORWARD"
        const val ACTION_PLAY_BOOK = "com.tortugapower.audiobookplayer.widget.large.ACTION_PLAY_BOOK"
        const val EXTRA_BOOK_UUID = "com.tortugapower.audiobookplayer.widget.large.EXTRA_BOOK_UUID"

        /**
         * Cheap play/pause-only refresh: patches the live widget in place instead of the full
         * rebuild (DB query + artwork loads + whole-RemoteViews Binder payload) that a broadcast
         * to onUpdate costs. Mirrors iOS's immediate-vs-coalesced widget reload split. Safe
         * because all three size layouts share the button id, tints persist as view properties
         * across setImageViewResource, and any launcher-side re-inflate goes through a full
         * onUpdate anyway (no stale-icon risk).
         */
        fun pushPlayStateUpdate(context: Context, isPlaying: Boolean) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                android.content.ComponentName(context, AudioWidgetLargeProvider::class.java)
            )
            if (ids.isEmpty()) return

            val views = RemoteViews(context.packageName, R.layout.audio_widget_large)
            views.setImageViewResource(
                R.id.widget_play_pause_btn,
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            )
            views.setContentDescription(
                R.id.widget_play_pause_btn,
                context.getString(if (isPlaying) R.string.player_pause else R.string.player_play)
            )
            try {
                manager.partiallyUpdateAppWidget(ids, views)
            } catch (e: Exception) {
                android.util.Log.e("AudioWidgetLarge", "Partial play-state update failed", e)
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        goAsyncLaunch { AudioWidgetLargeRenderer.refresh(context, appWidgetIds) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        val currentBook = PlaybackManager.currentItem.value
        val isPlaying = PlaybackManager.isPlaying.value
        goAsyncLaunch {
            val db = AppDatabase.getDatabase(context)
            AudioWidgetLargeRenderer.updateWidget(context, appWidgetManager, appWidgetId, newOptions, db, currentBook, isPlaying)
        }
    }

    /**
     * Runs [block] on the widget scope while holding the receiver alive via goAsync(); without it
     * the process becomes killable the moment onReceive/onUpdate returns, dropping in-flight
     * DB queries, artwork loads, and widget updates.
     *
     * Only broadcasts from outside the process arrive here — the launcher's APPWIDGET_* and the
     * widget's own tap PendingIntents. In-process refreshes call [AudioWidgetLargeRenderer.refresh]
     * directly; see there for why they must not broadcast at this receiver.
     */
    private fun goAsyncLaunch(block: suspend () -> Unit) {
        val pendingResult = goAsync()
        widgetScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("AudioWidgetLarge", "Widget work failed", e)
            } finally {
                try {
                    pendingResult.finish()
                } catch (e: IllegalStateException) {
                    // "Broadcast already finished": vivo's Funtouch 13 finished the result before we
                    // did, and the broadcast is over either way. Only this direct finish is
                    // catchable — when QueuedWork defers it, the throw lands on the framework's
                    // own thread — which is why in-process refreshes never come through here.
                    android.util.Log.w("AudioWidgetLarge", "PendingResult already finished", e)
                }
            }
        }
    }

    /** Awaits the MediaController (widget taps can cold-start the process) before controlling playback. */
    private fun runWithPlayer(block: () -> Unit) = goAsyncLaunch {
        if (PlaybackManager.awaitPlayer() != null) block()
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return

        when (action) {
            ACTION_PLAY_PAUSE -> runWithPlayer { PlaybackManager.togglePlayPause() }
            ACTION_REWIND -> runWithPlayer { PlaybackManager.seekBackward() }
            ACTION_FORWARD -> runWithPlayer { PlaybackManager.seekForward() }
            ACTION_PLAY_BOOK -> {
                val uuid = intent.getStringExtra(EXTRA_BOOK_UUID)
                if (uuid != null) {
                    goAsyncLaunch {
                        val db = AppDatabase.getDatabase(context)
                        val item = db.libraryDao().getItemById(uuid)
                        if (item != null && PlaybackManager.awaitPlayer() != null) {
                            PlaybackManager.playItem(context, item, autoplay = true)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The title's first letter, for the vertical list's cover placeholder — shared by the API 31+
 * provider path and the legacy RemoteViewsService path so the two can't drift.
 */
internal fun widgetPlaceholderInitial(title: String): String =
    title.trim().firstOrNull()?.uppercaseChar()?.toString() ?: ""
