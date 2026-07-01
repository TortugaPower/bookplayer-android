package com.tortugapower.audiobookplayer.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.widget.RemoteViews
import com.tortugapower.audiobookplayer.MainActivity
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.logic.PlaybackManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import android.graphics.drawable.BitmapDrawable

class AudioWidgetProvider : AppWidgetProvider() {

    private val widgetScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        const val ACTION_PLAY_PAUSE = "com.tortugapower.audiobookplayer.widget.ACTION_PLAY_PAUSE"
        const val ACTION_PREVIOUS = "com.tortugapower.audiobookplayer.widget.ACTION_PREVIOUS"
        const val ACTION_NEXT = "com.tortugapower.audiobookplayer.widget.ACTION_NEXT"
        const val ACTION_PLAY_BOOK = "com.tortugapower.audiobookplayer.widget.ACTION_PLAY_BOOK"
        const val EXTRA_BOOK_UUID = "com.tortugapower.audiobookplayer.widget.EXTRA_BOOK_UUID"
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val currentBook = PlaybackManager.currentItem.value
        val isPlaying = PlaybackManager.isPlaying.value

        widgetScope.launch {
            val db = AppDatabase.getDatabase(context)
            // Query top 5 recently played unfinished books
            val recentBooks = withContext(Dispatchers.IO) {
                db.libraryDao().getRecentUnfinishedBooksSync(5)
            }

            for (appWidgetId in appWidgetIds) {
                val views = RemoteViews(context.packageName, R.layout.audio_widget)

                // 1. Setup now playing header
                if (currentBook != null) {
                    views.setTextViewText(R.id.widget_title, currentBook.title)
                    views.setTextViewText(R.id.widget_author, currentBook.author ?: context.getString(R.string.library_unknown_author))

                    // Load artwork
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

                // 2. Open main app on tapping now playing area
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
                    getBroadcastIntent(context, ACTION_PREVIOUS, appWidgetId + 1)
                )
                views.setOnClickPendingIntent(
                    R.id.widget_next_btn,
                    getBroadcastIntent(context, ACTION_NEXT, appWidgetId + 2)
                )

                // 4. Setup recent items horizontal slots
                val slots = listOf(
                    Triple(R.id.widget_recent_1, R.id.widget_recent_artwork_1, Pair(0, R.id.widget_recent_placeholder_text_1)),
                    Triple(R.id.widget_recent_2, R.id.widget_recent_artwork_2, Pair(0, R.id.widget_recent_placeholder_text_2)),
                    Triple(R.id.widget_recent_3, R.id.widget_recent_artwork_3, Pair(0, R.id.widget_recent_placeholder_text_3)),
                    Triple(R.id.widget_recent_4, R.id.widget_recent_artwork_4, Pair(0, R.id.widget_recent_placeholder_text_4)),
                    Triple(R.id.widget_recent_5, R.id.widget_recent_artwork_5, Pair(0, R.id.widget_recent_placeholder_text_5))
                )

                for (i in 0 until 5) {
                    val slot = slots[i]
                    if (i < recentBooks.size) {
                        val book = recentBooks[i]
                        views.setViewVisibility(slot.first, View.VISIBLE)

                        val artworkPath = book.artworkURL
                        if (!artworkPath.isNullOrEmpty()) {
                            val bitmap = loadArtworkBitmap(context, artworkPath, 150)
                            if (bitmap != null) {
                                views.setImageViewBitmap(slot.second, bitmap)
                                views.setViewVisibility(slot.second, View.VISIBLE)
                                views.setViewVisibility(slot.third.second, View.GONE)
                            } else {
                                // Text fallback
                                val maxLetters = 15
                                val placeholderText = if (book.title.length > maxLetters) {
                                    book.title.substring(0, maxLetters - 3) + "..."
                                } else {
                                    book.title
                                }
                                views.setTextViewText(slot.third.second, placeholderText)
                                views.setViewVisibility(slot.second, View.GONE)
                                views.setViewVisibility(slot.third.second, View.VISIBLE)
                            }
                        } else {
                            // Text fallback
                            val maxLetters = 15
                            val placeholderText = if (book.title.length > maxLetters) {
                                book.title.substring(0, maxLetters - 3) + "..."
                            } else {
                                book.title
                            }
                            views.setTextViewText(slot.third.second, placeholderText)
                            views.setViewVisibility(slot.second, View.GONE)
                            views.setViewVisibility(slot.third.second, View.VISIBLE)
                        }

                        // Set item click intent
                        val clickIntent = Intent(context, AudioWidgetProvider::class.java).apply {
                            action = ACTION_PLAY_BOOK
                            putExtra(EXTRA_BOOK_UUID, book.uuid)
                        }
                        val pendingIntent = PendingIntent.getBroadcast(
                            context,
                            appWidgetId * 10 + i,
                            clickIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        views.setOnClickPendingIntent(slot.first, pendingIntent)
                    } else {
                        // Empty slot
                        views.setViewVisibility(slot.first, View.GONE)
                    }
                }

                // If no recent books exist, hide the recents container completely
                if (recentBooks.isEmpty()) {
                    views.setViewVisibility(R.id.widget_recents_container, View.GONE)
                } else {
                    views.setViewVisibility(R.id.widget_recents_container, View.VISIBLE)
                }

                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }
    }

    private fun getBroadcastIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, AudioWidgetProvider::class.java).apply {
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
            ACTION_PREVIOUS -> {
                PlaybackManager.playPrevious(context)
            }
            ACTION_NEXT -> {
                PlaybackManager.playNext(context)
            }
            ACTION_PLAY_BOOK -> {
                val uuid = intent.getStringExtra(EXTRA_BOOK_UUID)
                if (uuid != null) {
                    widgetScope.launch {
                        val db = AppDatabase.getDatabase(context)
                        val item = db.libraryDao().getItemById(uuid)
                        if (item != null) {
                            PlaybackManager.playItem(context, item, autoplay = true)
                        }
                    }
                }
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
