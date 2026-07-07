package com.tortugapower.audiobookplayer.logic

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.tortugapower.audiobookplayer.R
import kotlinx.coroutines.launch

object ShortcutHelper {

    fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable) {
            return drawable.bitmap
        }
        val w = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 150
        val h = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 150
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(canvas)
        return bmp
    }

    fun getShortcutIntentUri(itemUuid: String): String {
        return "bookplayer://play?identifier=$itemUuid&autoplay=true"
    }

    fun buildShortcutIntent(context: Context, itemUuid: String): Intent {
        // Explicitly target MainActivity (mirrors setupDynamicShortcuts): an implicit ACTION_VIEW
        // on the bookplayer:// scheme could be intercepted by any app registering the same
        // scheme, leaking the item UUID or hijacking the launch.
        return Intent(Intent.ACTION_VIEW, Uri.parse(getShortcutIntentUri(itemUuid)))
            .setClass(context, com.tortugapower.audiobookplayer.MainActivity::class.java)
    }

    fun getShortcutId(itemUuid: String): String {
        return "shortcut_play_$itemUuid"
    }

    /**
     * Fire-and-forget pin request for a library item — the single entry point for UI callers
     * (library long-press menu, player More sheet).
     */
    fun requestPinShortcut(
        context: Context,
        scope: kotlinx.coroutines.CoroutineScope,
        item: com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
    ) {
        scope.launch {
            createPinShortcut(
                context = context,
                itemUuid = item.uuid,
                itemTitle = item.title,
                itemArtworkUrl = item.artworkURL
            )
        }
    }

    suspend fun createPinShortcut(
        context: Context,
        itemUuid: String,
        itemTitle: String,
        itemArtworkUrl: String?
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val shortcutManager = context.getSystemService(ShortcutManager::class.java) ?: return
            if (shortcutManager.isRequestPinShortcutSupported) {
                val loader = context.imageLoader
                val request = ImageRequest.Builder(context)
                    .data(itemArtworkUrl ?: R.mipmap.ic_launcher)
                    .size(150)
                    .allowHardware(false)
                    .build()
                val result = loader.execute(request)
                val drawable = (result as? SuccessResult)?.drawable
                val bitmap = drawable?.let { drawableToBitmap(it) }
                val icon = if (bitmap != null) {
                    Icon.createWithBitmap(bitmap)
                } else {
                    Icon.createWithResource(context, R.mipmap.ic_launcher)
                }

                val pinShortcutInfo = ShortcutInfo.Builder(context, getShortcutId(itemUuid))
                    .setShortLabel(itemTitle)
                    .setLongLabel(context.getString(R.string.shortcut_play_item_desc, itemTitle))
                    .setIcon(icon)
                    .setIntent(buildShortcutIntent(context, itemUuid))
                    .build()
                shortcutManager.requestPinShortcut(pinShortcutInfo, null)
            }
        }
    }
}
