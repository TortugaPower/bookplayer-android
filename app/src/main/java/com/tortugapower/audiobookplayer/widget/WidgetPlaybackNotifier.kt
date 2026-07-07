package com.tortugapower.audiobookplayer.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Phone-side hook that keeps the home-screen widget in sync with playback. Wired into `PlaybackManager`'s
 * `onPlaybackStateChanged` callback (so `PlaybackManager` itself carries no widget dependency and can live in
 * `:core`).
 *
 * iOS-style split (WidgetReloadService): a book change is a rare transition that rebuilds the whole widget;
 * a play/pause flip only patches the button in place — no DB query, artwork reload, or full RemoteViews
 * payload per tap.
 */
object WidgetPlaybackNotifier {
    fun notify(context: Context, itemChanged: Boolean, isPlaying: Boolean) {
        if (itemChanged) {
            val largeIds = AppWidgetManager.getInstance(context).getAppWidgetIds(
                ComponentName(context, AudioWidgetLargeProvider::class.java)
            )
            // No widgets placed — skip the broadcast (also covers the combine's cold-start emission).
            if (largeIds.isNotEmpty()) {
                val largeIntent = Intent(context, AudioWidgetLargeProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                }
                largeIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, largeIds)
                context.sendBroadcast(largeIntent)
            }
        } else {
            AudioWidgetLargeProvider.pushPlayStateUpdate(context, isPlaying)
        }
    }
}
