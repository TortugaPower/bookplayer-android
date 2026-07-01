package com.tortugapower.audiobookplayer.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.RemoteViews
import com.tortugapower.audiobookplayer.MainActivity
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.logic.PlaybackManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import android.graphics.drawable.BitmapDrawable

class AudioWidgetSmallProvider : AppWidgetProvider() {

    private val widgetScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        const val ACTION_PLAY_PAUSE = "com.tortugapower.audiobookplayer.widget.small.ACTION_PLAY_PAUSE"
        const val ACTION_REWIND = "com.tortugapower.audiobookplayer.widget.small.ACTION_REWIND"
        const val ACTION_FORWARD = "com.tortugapower.audiobookplayer.widget.small.ACTION_FORWARD"
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val currentBook = PlaybackManager.currentItem.value
        val isPlaying = PlaybackManager.isPlaying.value

        widgetScope.launch {
            for (appWidgetId in appWidgetIds) {
                val views = RemoteViews(context.packageName, R.layout.audio_widget_small)

                // 1. Setup now playing details
                if (currentBook != null) {
                    views.setTextViewText(R.id.widget_title, currentBook.title)
                    views.setTextViewText(R.id.widget_author, currentBook.author ?: context.getString(R.string.library_unknown_author))

                    // Load artwork via Coil
                    val artworkPath = currentBook.artworkURL
                    if (!artworkPath.isNullOrEmpty()) {
                        val bitmap = loadArtworkBitmap(context, artworkPath, 200)
                        if (bitmap != null) {
                            views.setImageViewBitmap(R.id.widget_artwork, bitmap)
                        } else {
                            views.setImageViewResource(R.id.widget_artwork, R.mipmap.ic_launcher)
                        }
                    } else {
                        views.setImageViewResource(R.id.widget_artwork, R.mipmap.ic_launcher)
                    }

                    views.setImageViewResource(
                        R.id.widget_play_pause_btn,
                        if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                    )
                } else {
                    // No book loaded
                    views.setTextViewText(R.id.widget_title, context.getString(R.string.app_name))
                    views.setTextViewText(R.id.widget_author, "")
                    views.setImageViewResource(R.id.widget_artwork, R.mipmap.ic_launcher)
                    views.setImageViewResource(R.id.widget_play_pause_btn, R.drawable.ic_play)
                }

                // 2. Open main app on tapping container
                val appIntent = Intent(context, MainActivity::class.java).apply {
                    putExtra("OPEN_PLAYER", true)
                }
                val appPendingIntent = PendingIntent.getActivity(
                    context,
                    0,
                    appIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_now_playing_container, appPendingIntent)

                // 3. Playback control button PendingIntents
                views.setOnClickPendingIntent(
                    R.id.widget_play_pause_btn,
                    getBroadcastIntent(context, ACTION_PLAY_PAUSE, appWidgetId)
                )
                views.setOnClickPendingIntent(
                    R.id.widget_prev_btn,
                    getBroadcastIntent(context, ACTION_REWIND, appWidgetId + 1)
                )
                views.setOnClickPendingIntent(
                    R.id.widget_next_btn,
                    getBroadcastIntent(context, ACTION_FORWARD, appWidgetId + 2)
                )

                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }
    }

    private fun getBroadcastIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, AudioWidgetSmallProvider::class.java).apply {
            this.action = action
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return

        when (action) {
            ACTION_PLAY_PAUSE -> {
                PlaybackManager.togglePlayPause()
            }
            ACTION_REWIND -> {
                PlaybackManager.seekBackward()
            }
            ACTION_FORWARD -> {
                PlaybackManager.seekForward()
            }
        }
    }

    private suspend fun loadArtworkBitmap(context: Context, path: String, targetSize: Int): Bitmap? {
        return try {
            val loader = context.imageLoader
            val request = ImageRequest.Builder(context)
                .data(path)
                .size(targetSize)
                .allowHardware(false)
                .build()
            val result = loader.execute(request)
            if (result is SuccessResult) {
                (result.drawable as? BitmapDrawable)?.bitmap
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
