package com.tortugapower.audiobookplayer.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
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

class AudioWidgetLargeProvider : AppWidgetProvider() {

    private val widgetScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        const val ACTION_PLAY_PAUSE = "com.tortugapower.audiobookplayer.widget.large.ACTION_PLAY_PAUSE"
        const val ACTION_REWIND = "com.tortugapower.audiobookplayer.widget.large.ACTION_REWIND"
        const val ACTION_FORWARD = "com.tortugapower.audiobookplayer.widget.large.ACTION_FORWARD"
        const val ACTION_PLAY_BOOK = "com.tortugapower.audiobookplayer.widget.large.ACTION_PLAY_BOOK"
        const val EXTRA_BOOK_UUID = "com.tortugapower.audiobookplayer.widget.large.EXTRA_BOOK_UUID"
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val currentBook = PlaybackManager.currentItem.value
        val isPlaying = PlaybackManager.isPlaying.value

        widgetScope.launch {
            val db = AppDatabase.getDatabase(context)
            // Query top 10 recently played unfinished books
            val recentBooks = withContext(Dispatchers.IO) {
                db.libraryDao().getRecentUnfinishedBooksSync(10)
            }

            for (appWidgetId in appWidgetIds) {
                val views = RemoteViews(context.packageName, R.layout.audio_widget_large)

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

                // 4. Setup ListView adapter
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Modern API 31+ using RemoteCollectionItems
                    val itemsBuilder = RemoteViews.RemoteCollectionItems.Builder()
                    val appIcons = intArrayOf(
                        R.mipmap.ic_launcher,
                        R.mipmap.ic_launcher_retro,
                        R.mipmap.ic_launcher_fruit_based,
                        R.mipmap.ic_launcher_retro_modern,
                        R.mipmap.ic_launcher_neon,
                        R.mipmap.ic_launcher_ayu_light,
                        R.mipmap.ic_launcher_songs
                    )

                    for (i in 0 until recentBooks.size) {
                        val book = recentBooks[i]
                        val itemViews = RemoteViews(context.packageName, R.layout.widget_large_list_item)
                        itemViews.setTextViewText(R.id.widget_item_title, book.title)
                        itemViews.setTextViewText(R.id.widget_item_author, book.author ?: context.getString(R.string.library_unknown_author))

                        val iconIndex = Math.abs(book.uuid.hashCode()) % appIcons.size
                        val fallbackIcon = appIcons[iconIndex]

                        val artworkPath = book.artworkURL
                        if (!artworkPath.isNullOrEmpty()) {
                            val bitmap = loadArtworkBitmap(context, artworkPath, 150)
                            if (bitmap != null) {
                                itemViews.setImageViewBitmap(R.id.widget_item_artwork, bitmap)
                                itemViews.setViewVisibility(R.id.widget_item_artwork, View.VISIBLE)
                                itemViews.setViewVisibility(R.id.widget_item_placeholder_text, View.GONE)
                            } else {
                                itemViews.setImageViewResource(R.id.widget_item_artwork, fallbackIcon)
                                itemViews.setViewVisibility(R.id.widget_item_artwork, View.VISIBLE)
                                itemViews.setViewVisibility(R.id.widget_item_placeholder_text, View.GONE)
                            }
                        } else {
                            itemViews.setImageViewResource(R.id.widget_item_artwork, fallbackIcon)
                            itemViews.setViewVisibility(R.id.widget_item_artwork, View.VISIBLE)
                            itemViews.setViewVisibility(R.id.widget_item_placeholder_text, View.GONE)
                        }

                        val fillInIntent = Intent().apply {
                            putExtra(EXTRA_BOOK_UUID, book.uuid)
                        }
                        itemViews.setOnClickFillInIntent(R.id.widget_item_container, fillInIntent)
                        itemsBuilder.addItem(i.toLong(), itemViews)
                    }
                    views.setRemoteAdapter(R.id.widget_list, itemsBuilder.build())
                } else {
                    // Legacy fallback using reflection to bypass compiler deprecation warnings
                    val serviceIntent = Intent(context, AudioWidgetLargeService::class.java).apply {
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                        data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
                    }
                    try {
                        val setAdapterMethod = RemoteViews::class.java.getMethod(
                            "setRemoteAdapter",
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType,
                            Intent::class.java
                        )
                        setAdapterMethod.invoke(views, appWidgetId, R.id.widget_list, serviceIntent)
                    } catch (e: Exception) {
                        // Safe fallback
                    }
                }
                views.setEmptyView(R.id.widget_list, R.id.widget_empty_view)

                // Item click PendingIntent template
                val clickIntent = Intent(context, AudioWidgetLargeProvider::class.java).apply {
                    action = ACTION_PLAY_BOOK
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                }
                val clickPendingIntent = PendingIntent.getBroadcast(
                    context,
                    appWidgetId * 10,
                    clickIntent,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    } else {
                        PendingIntent.FLAG_UPDATE_CURRENT
                    }
                )
                views.setPendingIntentTemplate(R.id.widget_list, clickPendingIntent)

                appWidgetManager.updateAppWidget(appWidgetId, views)

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    // Legacy update trigger using reflection to bypass compiler deprecation warnings
                    try {
                        val notifyMethod = AppWidgetManager::class.java.getMethod(
                            "notifyAppWidgetViewDataChanged",
                            IntArray::class.java,
                            Int::class.javaPrimitiveType
                        )
                        notifyMethod.invoke(appWidgetManager, intArrayOf(appWidgetId), R.id.widget_list)
                    } catch (e: Exception) {
                        // Safe fallback
                    }
                }
            }
        }
    }

    private fun getBroadcastIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, AudioWidgetLargeProvider::class.java).apply {
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
