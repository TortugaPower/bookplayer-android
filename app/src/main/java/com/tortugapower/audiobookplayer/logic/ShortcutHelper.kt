package com.tortugapower.audiobookplayer.logic

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.widget.Toast
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.tortugapower.audiobookplayer.MainActivity
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import io.sentry.Sentry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Single owner of the app's launcher shortcuts — both the fixed *dynamic* set (long-press the launcher
 * icon) and *pinned* per-book shortcuts. Uses the AndroidX [ShortcutManagerCompat] wrappers so version
 * gating is handled for us (no `Build.VERSION` guards). All shortcut intents are built here so the
 * anti-hijack invariant (explicit MainActivity target) lives in one place.
 */
object ShortcutHelper {

    // Dynamic (launcher long-press) shortcut ids — the fixed set we own; also the idempotency key.
    private const val ID_PLAY_LAST = "shortcut_play_last"
    private const val ID_REWIND = "shortcut_rewind"
    private const val ID_FORWARD = "shortcut_forward"
    private const val ID_SLEEP = "shortcut_sleep_timer"

    // Application-lifetime scope for fire-and-forget pin requests. Owning the scope here (instead of
    // taking the caller's) means a config change mid artwork-fetch can't cancel the request — a UI
    // scope such as rememberCoroutineScope() is cancelled and recreated on rotation.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + com.tortugapower.audiobookplayer.logic.StorageMonitor.exceptionHandler { com.tortugapower.audiobookplayer.core.CoreContext.appContextOrNull })

    fun getShortcutId(itemUuid: String): String = "shortcut_play_$itemUuid"

    fun getShortcutIntentUri(itemUuid: String): String =
        "bookplayer://play?identifier=$itemUuid&autoplay=true"

    // Explicitly target MainActivity: an implicit ACTION_VIEW on the bookplayer:// scheme could be
    // intercepted by any app registering the same scheme, leaking the item uuid or hijacking the launch.
    private fun intentForDeepLink(context: Context, deepLink: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(deepLink)).setClass(context, MainActivity::class.java)

    /** The pinned "play this book" intent (kept as its own entry point; unit-tested for the target invariant). */
    fun buildShortcutIntent(context: Context, itemUuid: String): Intent =
        intentForDeepLink(context, getShortcutIntentUri(itemUuid))

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

    /**
     * Fire-and-forget pin request for a library item — the single entry point for UI callers (library
     * long-press menu, player More sheet). Runs on [scope] so it survives the caller's config changes.
     */
    fun requestPinShortcut(context: Context, item: LibraryItemEntity) {
        // Only the application context may cross into the coroutine — an Activity context would leak the
        // destroyed Activity across the (seconds-long) artwork fetch on a rotation.
        val appContext = context.applicationContext
        // Resolve the cover the same way the library list does (LibraryScreen): the stored artworkURL
        // when present, else the embedded-art model (EmbeddedArtworkFetcher extracts it from the local
        // processed file, else streams it from the remote URL). artworkURL is null for most items, so
        // without this the icon would always fall back to the launcher icon.
        val artworkModel: Any = item.artworkURL ?: ItemArtwork(item.uuid, item.relativePath, item.remoteURL)
        scope.launch {
            createPinShortcut(appContext, item.uuid, item.title, artworkModel)
        }
    }

    suspend fun createPinShortcut(
        context: Context,
        itemUuid: String,
        itemTitle: String,
        artworkModel: Any?
    ) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            // Some launchers don't support pinning; without feedback the menu item reads as broken.
            Toast.makeText(context, R.string.shortcut_pin_failed, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val loader = context.imageLoader
            val request = ImageRequest.Builder(context)
                .data(artworkModel ?: R.mipmap.ic_launcher)
                .size(150)
                .allowHardware(false)
                .build()
            // A null result (no stored art, no embedded art, unreachable remote) falls back to the icon.
            val drawable = (loader.execute(request) as? SuccessResult)?.drawable
            // Non-adaptive bitmap: book covers are full images, not icon layers. An adaptive bitmap would
            // be scaled to the 108dp bleed and masked to the ~72dp safe zone, cropping a portrait cover's
            // top and bottom to a center band.
            val icon = drawable?.let { IconCompat.createWithBitmap(drawableToBitmap(it)) }
                ?: IconCompat.createWithResource(context, R.mipmap.ic_launcher)

            val pinShortcutInfo = ShortcutInfoCompat.Builder(context, getShortcutId(itemUuid))
                .setShortLabel(itemTitle)
                .setLongLabel(context.getString(R.string.shortcut_play_item_desc, itemTitle))
                .setIcon(icon)
                .setIntent(buildShortcutIntent(context, itemUuid))
                .build()
            ShortcutManagerCompat.requestPinShortcut(context, pinShortcutInfo, null)
        } catch (e: Exception) {
            // requestPinShortcut throws on locked profiles and on some OEMs once the app is no longer
            // foreground — a window the async artwork fetch above makes likely. This is fire-and-forget,
            // so an uncaught throw would crash the process.
            Sentry.captureException(e)
            Toast.makeText(context, R.string.shortcut_pin_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Publish the fixed set of dynamic (launcher long-press) shortcuts. Idempotent: skips the disk write
     * when the expected shortcuts already exist with the current-locale "play last" label — so ordinary
     * recreations (rotation/dark-mode) are a no-op, while a locale change still refreshes translated
     * labels. Safe to call off the main thread (the callers dispatch it during cold start).
     */
    fun publishDynamicShortcuts(context: Context) {
        val playLastLabel = context.getString(R.string.shortcut_play_last_title)
        val existing = ShortcutManagerCompat.getDynamicShortcuts(context)
        val expectedIds = setOf(ID_PLAY_LAST, ID_REWIND, ID_FORWARD, ID_SLEEP)
        val upToDate = existing.map { it.id }.toSet() == expectedIds &&
            existing.any { it.id == ID_PLAY_LAST && it.shortLabel == playLastLabel }
        if (upToDate) return

        fun launcherIcon() = IconCompat.createWithResource(context, R.mipmap.ic_launcher)
        val shortcuts = listOf(
            ShortcutInfoCompat.Builder(context, ID_PLAY_LAST)
                .setShortLabel(playLastLabel)
                .setLongLabel(context.getString(R.string.shortcut_play_last_desc))
                .setIcon(launcherIcon())
                .setIntent(intentForDeepLink(context, "bookplayer://play?autoplay=true"))
                .build(),
            ShortcutInfoCompat.Builder(context, ID_REWIND)
                .setShortLabel(context.getString(R.string.shortcut_rewind_title))
                .setIcon(launcherIcon())
                .setIntent(intentForDeepLink(context, "bookplayer://skipRewind"))
                .build(),
            ShortcutInfoCompat.Builder(context, ID_FORWARD)
                .setShortLabel(context.getString(R.string.shortcut_forward_title))
                .setIcon(launcherIcon())
                .setIntent(intentForDeepLink(context, "bookplayer://skipForward"))
                .build(),
            ShortcutInfoCompat.Builder(context, ID_SLEEP)
                .setShortLabel(context.getString(R.string.shortcut_sleep_timer_title))
                .setIcon(launcherIcon())
                .setIntent(intentForDeepLink(context, "bookplayer://sleep"))
                .build(),
        )
        ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
    }
}
