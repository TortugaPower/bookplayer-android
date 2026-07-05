package com.tortugapower.audiobookplayer.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import kotlinx.coroutines.runBlocking
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import android.graphics.drawable.BitmapDrawable

class AudioWidgetLargeService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return AudioWidgetLargeFactory(applicationContext)
    }
}

class AudioWidgetLargeFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {

    private var itemsList = listOf<LibraryItemEntity>()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        runBlocking {
            val db = AppDatabase.getDatabase(context)
            // Query 10 recently played unfinished books to fill the vertical list
            itemsList = db.libraryDao().getRecentUnfinishedBooksSync(10)
        }
    }

    override fun onDestroy() {}

    override fun getCount(): Int = itemsList.size

    override fun getViewAt(position: Int): RemoteViews {
        if (position >= itemsList.size) return RemoteViews(context.packageName, R.layout.widget_large_list_item)
        val item = itemsList[position]

        val views = RemoteViews(context.packageName, R.layout.widget_large_list_item)
        views.setTextViewText(R.id.widget_item_title, item.title)
        views.setTextViewText(R.id.widget_item_author, item.author ?: context.getString(R.string.library_unknown_author))

        val appIcons = intArrayOf(
            R.mipmap.ic_launcher,
            R.mipmap.ic_launcher_retro,
            R.mipmap.ic_launcher_fruit_based,
            R.mipmap.ic_launcher_retro_modern,
            R.mipmap.ic_launcher_neon,
            R.mipmap.ic_launcher_ayu_light,
            R.mipmap.ic_launcher_songs
        )
        val iconIndex = Math.abs(item.uuid.hashCode()) % appIcons.size
        val fallbackIcon = appIcons[iconIndex]

        val artworkPath = item.artworkURL
        if (!artworkPath.isNullOrEmpty()) {
            val bitmap = runBlocking {
                loadArtworkBitmap(context, artworkPath, 150)
            }
            if (bitmap != null) {
                views.setImageViewBitmap(R.id.widget_item_artwork, bitmap)
                views.setViewVisibility(R.id.widget_item_artwork, View.VISIBLE)
                views.setViewVisibility(R.id.widget_item_placeholder_text, View.GONE)
            } else {
                views.setImageViewResource(R.id.widget_item_artwork, fallbackIcon)
                views.setViewVisibility(R.id.widget_item_artwork, View.VISIBLE)
                views.setViewVisibility(R.id.widget_item_placeholder_text, View.GONE)
            }
        } else {
            views.setImageViewResource(R.id.widget_item_artwork, fallbackIcon)
            views.setViewVisibility(R.id.widget_item_artwork, View.VISIBLE)
            views.setViewVisibility(R.id.widget_item_placeholder_text, View.GONE)
        }

        // Fill-in Intent for clicks
        val fillInIntent = Intent().apply {
            putExtra(AudioWidgetLargeProvider.EXTRA_BOOK_UUID, item.uuid)
        }
        views.setOnClickFillInIntent(R.id.widget_item_container, fillInIntent)

        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds(): Boolean = true

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
