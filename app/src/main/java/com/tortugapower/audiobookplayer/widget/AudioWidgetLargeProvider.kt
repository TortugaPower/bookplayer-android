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
import com.tortugapower.audiobookplayer.logic.ThemeManager
import com.tortugapower.audiobookplayer.logic.PlaybackSettingsManager
import kotlinx.coroutines.flow.first
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
            for (appWidgetId in appWidgetIds) {
                val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
                updateWidget(context, appWidgetManager, appWidgetId, options, db, currentBook, isPlaying)
            }
        }
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
        widgetScope.launch {
            val db = AppDatabase.getDatabase(context)
            updateWidget(context, appWidgetManager, appWidgetId, newOptions, db, currentBook, isPlaying)
        }
    }

    private suspend fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        options: android.os.Bundle?,
        db: AppDatabase,
        currentBook: com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity?,
        isPlaying: Boolean
    ) {
        val minWidth = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
        val minHeight = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0
        val maxWidth = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0) ?: 0
        val maxHeight = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0) ?: 0
        android.util.Log.d("AudioWidgetLarge", "Resized details - id: $appWidgetId, minW: $minWidth, minH: $minHeight, maxW: $maxWidth, maxH: $maxHeight")

        val colors = getWidgetThemeColors(context)

        val isSmallHeight = minHeight in 75..139
        val isTinyHeight = minHeight in 1..74

        val layoutRes = when {
            isTinyHeight -> R.layout.audio_widget_small
            isSmallHeight -> R.layout.audio_widget
            else -> R.layout.audio_widget_large
        }
        val views = RemoteViews(context.packageName, layoutRes)

        // Apply theme colors to container backgrounds and dividers
        val bgStateList = android.content.res.ColorStateList.valueOf(colors.backgroundColor)
        views.setColorStateList(R.id.widget_card_container, "setBackgroundTintList", bgStateList)
        views.setColorStateList(R.id.widget_artwork_container, "setBackgroundTintList", android.content.res.ColorStateList.valueOf(colors.placeholderBgColor))
        if (!isTinyHeight) {
            views.setInt(R.id.widget_divider, "setBackgroundColor", colors.separatorColor)
            views.setTextColor(R.id.widget_empty_view, colors.textColorSecondary)
        }

        // Apply theme colors to media control buttons
        views.setColorStateList(R.id.widget_play_pause_btn, "setImageTintList", android.content.res.ColorStateList.valueOf(colors.accentColor))
        views.setColorStateList(R.id.widget_prev_btn, "setImageTintList", android.content.res.ColorStateList.valueOf(colors.textColorPrimary))
        views.setColorStateList(R.id.widget_next_btn, "setImageTintList", android.content.res.ColorStateList.valueOf(colors.textColorPrimary))

        // 1. Setup now playing details
        if (currentBook != null) {
            views.setTextViewText(R.id.widget_title, currentBook.title)
            views.setTextViewText(R.id.widget_author, currentBook.author ?: context.getString(R.string.library_unknown_author))
            views.setTextColor(R.id.widget_title, colors.textColorPrimary)
            views.setTextColor(R.id.widget_author, colors.textColorSecondary)

            // Load artwork via Coil
            val artworkPath = currentBook.artworkURL
            if (!artworkPath.isNullOrEmpty()) {
                val bitmap = loadArtworkBitmap(context, artworkPath, 200)
                if (bitmap != null) {
                    views.setInt(R.id.widget_artwork, "setBackgroundColor", android.graphics.Color.TRANSPARENT)
                    views.setImageViewBitmap(R.id.widget_artwork, bitmap)
                } else {
                    val fallbackBitmap = loadArtworkBitmap(context, R.mipmap.ic_launcher, 200)
                    if (fallbackBitmap != null) {
                        val bgColor = detectBackgroundColor(fallbackBitmap)
                        views.setInt(R.id.widget_artwork, "setBackgroundColor", bgColor)
                        views.setImageViewBitmap(R.id.widget_artwork, fallbackBitmap)
                    } else {
                        views.setInt(R.id.widget_artwork, "setBackgroundColor", android.graphics.Color.TRANSPARENT)
                        views.setImageViewResource(R.id.widget_artwork, R.mipmap.ic_launcher)
                    }
                }
            } else {
                val fallbackBitmap = loadArtworkBitmap(context, R.mipmap.ic_launcher, 200)
                if (fallbackBitmap != null) {
                    val bgColor = detectBackgroundColor(fallbackBitmap)
                    views.setInt(R.id.widget_artwork, "setBackgroundColor", bgColor)
                    views.setImageViewBitmap(R.id.widget_artwork, fallbackBitmap)
                } else {
                    views.setInt(R.id.widget_artwork, "setBackgroundColor", android.graphics.Color.TRANSPARENT)
                    views.setImageViewResource(R.id.widget_artwork, R.mipmap.ic_launcher)
                }
            }

            views.setImageViewResource(
                R.id.widget_play_pause_btn,
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            )
        } else {
            // No book loaded
            views.setTextViewText(R.id.widget_title, context.getString(R.string.app_name))
            views.setTextViewText(R.id.widget_author, "")
            views.setTextColor(R.id.widget_title, colors.textColorPrimary)
            views.setTextColor(R.id.widget_author, colors.textColorSecondary)
            val fallbackBitmap = loadArtworkBitmap(context, R.mipmap.ic_launcher, 200)
            if (fallbackBitmap != null) {
                val bgColor = detectBackgroundColor(fallbackBitmap)
                views.setInt(R.id.widget_artwork, "setBackgroundColor", bgColor)
                views.setImageViewBitmap(R.id.widget_artwork, fallbackBitmap)
            } else {
                views.setInt(R.id.widget_artwork, "setBackgroundColor", android.graphics.Color.TRANSPARENT)
                views.setImageViewResource(R.id.widget_artwork, R.mipmap.ic_launcher)
            }
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
        val tapContainerId = if (isTinyHeight) R.id.widget_card_container else R.id.widget_now_playing_container
        views.setOnClickPendingIntent(tapContainerId, appPendingIntent)

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

        if (isSmallHeight) {
            val recentBooks = withContext(Dispatchers.IO) {
                db.libraryDao().getRecentUnfinishedBooksSync(6)
            }

            val slots = listOf(
                Triple(R.id.widget_recent_1, R.id.widget_recent_artwork_1, Pair(0, R.id.widget_recent_placeholder_text_1)),
                Triple(R.id.widget_recent_2, R.id.widget_recent_artwork_2, Pair(0, R.id.widget_recent_placeholder_text_2)),
                Triple(R.id.widget_recent_3, R.id.widget_recent_artwork_3, Pair(0, R.id.widget_recent_placeholder_text_3)),
                Triple(R.id.widget_recent_4, R.id.widget_recent_artwork_4, Pair(0, R.id.widget_recent_placeholder_text_4)),
                Triple(R.id.widget_recent_5, R.id.widget_recent_artwork_5, Pair(0, R.id.widget_recent_placeholder_text_5)),
                Triple(R.id.widget_recent_6, R.id.widget_recent_artwork_6, Pair(0, R.id.widget_recent_placeholder_text_6))
            )

            for (i in 0 until 6) {
                val slot = slots[i]
                if (i < recentBooks.size) {
                    val book = recentBooks[i]
                    views.setViewVisibility(slot.first, View.VISIBLE)
                    views.setColorStateList(slot.first, "setBackgroundTintList", android.content.res.ColorStateList.valueOf(colors.placeholderBgColor))
                    views.setTextColor(slot.third.second, colors.textColorPrimary)

                    val artworkPath = book.artworkURL
                    if (!artworkPath.isNullOrEmpty()) {
                        val bitmap = loadArtworkBitmap(context, artworkPath, 150)
                        if (bitmap != null) {
                            views.setImageViewBitmap(slot.second, bitmap)
                            views.setViewVisibility(slot.second, View.VISIBLE)
                            views.setViewVisibility(slot.third.second, View.GONE)
                        } else {
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
                    val clickIntent = Intent(context, AudioWidgetLargeProvider::class.java).apply {
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
                    views.setViewVisibility(slot.first, View.GONE)
                }
            }

            if (recentBooks.isEmpty()) {
                views.setViewVisibility(R.id.widget_recents_container, View.GONE)
            } else {
                views.setViewVisibility(R.id.widget_recents_container, View.VISIBLE)
            }
        } else if (!isTinyHeight) {
            val recentBooks = withContext(Dispatchers.IO) {
                db.libraryDao().getRecentUnfinishedBooksSync(10)
            }

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
                    itemViews.setTextColor(R.id.widget_item_title, colors.textColorPrimary)
                    itemViews.setTextColor(R.id.widget_item_author, colors.textColorSecondary)
                    itemViews.setTextColor(R.id.widget_item_placeholder_text, colors.textColorPrimary)
                    itemViews.setColorStateList(R.id.widget_item_artwork_container, "setBackgroundTintList", android.content.res.ColorStateList.valueOf(colors.placeholderBgColor))

                    val iconIndex = Math.abs(book.uuid.hashCode()) % appIcons.size
                    val fallbackIcon = appIcons[iconIndex]

                    val artworkPath = book.artworkURL
                    if (!artworkPath.isNullOrEmpty()) {
                        val bitmap = loadArtworkBitmap(context, artworkPath, 150)
                        if (bitmap != null) {
                            itemViews.setInt(R.id.widget_item_artwork, "setBackgroundColor", android.graphics.Color.TRANSPARENT)
                            itemViews.setImageViewBitmap(R.id.widget_item_artwork, bitmap)
                            itemViews.setViewVisibility(R.id.widget_item_artwork, View.VISIBLE)
                            itemViews.setViewVisibility(R.id.widget_item_placeholder_text, View.GONE)
                        } else {
                            val fallbackBitmap = loadArtworkBitmap(context, fallbackIcon, 150)
                            if (fallbackBitmap != null) {
                                val bgColor = detectBackgroundColor(fallbackBitmap)
                                itemViews.setInt(R.id.widget_item_artwork, "setBackgroundColor", bgColor)
                                itemViews.setImageViewBitmap(R.id.widget_item_artwork, fallbackBitmap)
                            } else {
                                itemViews.setInt(R.id.widget_item_artwork, "setBackgroundColor", android.graphics.Color.TRANSPARENT)
                                itemViews.setImageViewResource(R.id.widget_item_artwork, fallbackIcon)
                            }
                            itemViews.setViewVisibility(R.id.widget_item_artwork, View.VISIBLE)
                            itemViews.setViewVisibility(R.id.widget_item_placeholder_text, View.GONE)
                        }
                    } else {
                        val fallbackBitmap = loadArtworkBitmap(context, fallbackIcon, 150)
                        if (fallbackBitmap != null) {
                            val bgColor = detectBackgroundColor(fallbackBitmap)
                            itemViews.setInt(R.id.widget_item_artwork, "setBackgroundColor", bgColor)
                            itemViews.setImageViewBitmap(R.id.widget_item_artwork, fallbackBitmap)
                        } else {
                            itemViews.setInt(R.id.widget_item_artwork, "setBackgroundColor", android.graphics.Color.TRANSPARENT)
                            itemViews.setImageViewResource(R.id.widget_item_artwork, fallbackIcon)
                        }
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
        }

        appWidgetManager.updateAppWidget(appWidgetId, views)

        if (!isSmallHeight && !isTinyHeight && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
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

    private suspend fun loadArtworkBitmap(context: Context, data: Any, targetSize: Int): Bitmap? {
        return try {
            val loader = context.imageLoader
            val request = ImageRequest.Builder(context)
                .data(data)
                .size(targetSize)
                .allowHardware(false)
                .build()
            val result = loader.execute(request)
            if (result is SuccessResult) {
                drawableToBitmap(result.drawable)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun detectBackgroundColor(bitmap: Bitmap): Int {
        val candidates = listOf(
            Pair(bitmap.width / 2, bitmap.height / 12),
            Pair(bitmap.width / 12, bitmap.height / 2),
            Pair(bitmap.width - bitmap.width / 12, bitmap.height / 2),
            Pair(bitmap.width / 2, bitmap.height - bitmap.height / 12),
            Pair(2, 2)
        )
        for (candidate in candidates) {
            val x = candidate.first.coerceIn(0, bitmap.width - 1)
            val y = candidate.second.coerceIn(0, bitmap.height - 1)
            val pixel = bitmap.getPixel(x, y)
            val alpha = (pixel shr 24) and 0xff
            if (alpha > 50) {
                return pixel
            }
        }
        return 0xFF404040.toInt()
    }

    private fun drawableToBitmap(drawable: android.graphics.drawable.Drawable): Bitmap {
        if (drawable is BitmapDrawable) {
            return drawable.bitmap
        }
        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 150
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 150
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private suspend fun getWidgetThemeColors(context: Context): WidgetThemeColors {
        val theme = if (ThemeManager.isReady) {
            ThemeManager.currentTheme
        } else {
            val loaded = try {
                context.assets.open("Themes.json").use { stream ->
                    java.io.InputStreamReader(stream, Charsets.UTF_8).use { reader ->
                        val listType = object : com.google.gson.reflect.TypeToken<List<com.tortugapower.audiobookplayer.ui.theme.BookPlayerThemeSpec>>() {}.type
                        com.google.gson.Gson().fromJson<List<com.tortugapower.audiobookplayer.ui.theme.BookPlayerThemeSpec>>(reader, listType)
                    }
                }
            } catch (e: Exception) {
                null
            } ?: emptyList()

            val savedTitle = try {
                PlaybackSettingsManager.getThemeTitle(context).first()
            } catch(e: Exception) {
                "Default / Dark"
            }
            loaded.firstOrNull { it.title == savedTitle } ?: loaded.firstOrNull() ?: ThemeManager.currentTheme
        }

        val useSystemMode = if (ThemeManager.isReady) ThemeManager.useSystemMode else {
            try { PlaybackSettingsManager.getUseSystemMode(context).first() } catch (e: Exception) { true }
        }
        val useDarkVariant = if (ThemeManager.isReady) ThemeManager.useDarkVariant else {
            try { PlaybackSettingsManager.getUseDarkVariant(context).first() } catch (e: Exception) { true }
        }

        val isSystemDark = (context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        val isDark = if (useSystemMode) isSystemDark else useDarkVariant

        val parseColor = { hex: String ->
            try {
                android.graphics.Color.parseColor("#$hex")
            } catch (e: Exception) {
                if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK
            }
        }

        val parseBgColor = { hex: String ->
            try {
                val colorInt = android.graphics.Color.parseColor("#$hex")
                val r = android.graphics.Color.red(colorInt)
                val g = android.graphics.Color.green(colorInt)
                val b = android.graphics.Color.blue(colorInt)
                android.graphics.Color.argb(0xE6, r, g, b)
            } catch (e: Exception) {
                if (isDark) 0xE6121212.toInt() else 0xE6FAFAFA.toInt()
            }
        }

        return if (isDark) {
            WidgetThemeColors(
                backgroundColor = parseBgColor(theme.darkSystemBackgroundHex),
                textColorPrimary = parseColor(theme.darkPrimaryHex),
                textColorSecondary = parseColor(theme.darkSecondaryHex),
                accentColor = parseColor(theme.darkAccentHex),
                separatorColor = parseColor(theme.darkSeparatorHex),
                placeholderBgColor = parseColor(theme.darkSecondarySystemBackgroundHex)
            )
        } else {
            WidgetThemeColors(
                backgroundColor = parseBgColor(theme.lightSystemBackgroundHex),
                textColorPrimary = parseColor(theme.lightPrimaryHex),
                textColorSecondary = parseColor(theme.lightSecondaryHex),
                accentColor = parseColor(theme.lightAccentHex),
                separatorColor = parseColor(theme.lightSeparatorHex),
                placeholderBgColor = parseColor(theme.lightSecondarySystemBackgroundHex)
            )
        }
    }

    data class WidgetThemeColors(
        val backgroundColor: Int,
        val textColorPrimary: Int,
        val textColorSecondary: Int,
        val accentColor: Int,
        val separatorColor: Int,
        val placeholderBgColor: Int
    )
}
