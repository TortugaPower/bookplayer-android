package com.tortugapower.audiobookplayer.wear.tile

import android.content.Context
import android.os.Build
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.CircularProgressIndicator
import androidx.wear.protolayout.material.Colors
import androidx.wear.protolayout.material.ProgressIndicatorColors
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.EdgeContentLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.common.util.concurrent.ListenableFuture
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.datalayer.WatchRemoteCodec
import com.tortugapower.audiobookplayer.logic.PlaybackManager
import com.tortugapower.audiobookplayer.datalayer.WearDataLayer
import com.tortugapower.audiobookplayer.wear.R
import com.tortugapower.audiobookplayer.wear.data.await
import com.tortugapower.audiobookplayer.wear.presentation.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.withContext

/**
 * A glanceable "now playing" [TileService]: shows the last-played book (title + author) with a whole-book
 * progress arc; tapping the tile opens the app (now-playing). Reads the last-played row from the shared DB,
 * so it works without the player being connected. The layout is a pure function of [TileGlanceState].
 */
class NowPlayingTileService : TileService() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> = scope.future {
        val layout = tileLayout(applicationContext, requestParams.deviceConfiguration, glanceState())
        TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
            .build()
    }

    /**
     * Branch on the actual mode: STANDALONE (PRO) plays locally, so the live loaded item wins (falling back
     * to the last-played DB row). REMOTE (free) plays on the phone — the watch's local player is stale
     * (a singleton that lingers from a prior PRO session), so it MUST be ignored in favor of the phone's
     * published now-playing.
     */
    private suspend fun glanceState(): TileGlanceState = withContext(Dispatchers.IO) {
        val db = AppDatabase.getDatabase(applicationContext)
        val isPro = db.accountDao().getAccount()?.tier == AccountTier.PRO
        if (isPro) {
            val current = PlaybackManager.currentItem.value
            if (current != null) TileGlanceState.from(current)
            else TileGlanceState.from(db.libraryDao().getRecentPlayedItemsSync(1).firstOrNull())
        } else {
            TileGlanceState.fromRemote(publishedLibraryState())
        }
    }

    /** One-shot read of the phone's latest published library DataItem (null if none / unreadable). */
    private suspend fun publishedLibraryState() = try {
        val items = Wearable.getDataClient(applicationContext).getDataItems().await()
        try {
            items.firstOrNull { it.uri.path == WearDataLayer.PATH_LIBRARY_STATE }
                ?.let { DataMapItem.fromDataItem(it).dataMap.getByteArray(WearDataLayer.KEY_PAYLOAD) }
                ?.let { WatchRemoteCodec.decodeLibraryState(it) }
        } finally {
            items.release()
        }
    } catch (e: Exception) {
        null
    }

    // No image resources — the tile is text + a progress arc — so resources is just the version stamp.
    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> = scope.future {
        ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val RESOURCES_VERSION = "1"
    }
}

/** The whole tile is clickable → launch [MainActivity] (now-playing). */
private fun openAppClickable(context: Context): ModifiersBuilders.Clickable =
    ModifiersBuilders.Clickable.Builder()
        .setId("open_now_playing")
        .setOnClick(
            ActionBuilders.LaunchAction.Builder()
                .setAndroidActivity(
                    ActionBuilders.AndroidActivity.Builder()
                        .setPackageName(context.packageName)
                        .setClassName(MainActivity::class.java.name)
                        .build(),
                ).build(),
        ).build()

/**
 * The watch's Material You colors on Wear 4+ (the system dynamic palette, so the tile matches the active
 * watch theme), falling back to the ProtoLayout Material default on older watches.
 */
private fun tileColors(context: Context): Colors =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Colors(
            /* primary   */ context.getColor(android.R.color.system_accent1_200),
            /* onPrimary  */ context.getColor(android.R.color.system_accent1_900),
            /* surface    */ context.getColor(android.R.color.system_neutral1_800),
            /* onSurface  */ context.getColor(android.R.color.system_neutral1_50),
        )
    } else {
        Colors.DEFAULT
    }

/** Pure-ish ProtoLayout for the glance state: progress arc on the edge, title + author in the middle. */
private fun tileLayout(context: Context, deviceParams: DeviceParameters, state: TileGlanceState): LayoutElement {
    val title = if (state.hasItem) state.title else context.getString(R.string.wear_tile_empty_title)
    val subtitle = if (state.hasItem) state.subtitle else context.getString(R.string.wear_tile_empty_subtitle)
    val colors = tileColors(context)

    val content = EdgeContentLayout.Builder(deviceParams)
        .setEdgeContent(
            CircularProgressIndicator.Builder()
                .setProgress(state.progress)
                .setCircularProgressIndicatorColors(ProgressIndicatorColors(colors.primary, colors.surface))
                .build(),
        )
        .setContent(
            Text.Builder(context, title)
                .setTypography(Typography.TYPOGRAPHY_TITLE3)
                .setColor(argb(colors.onSurface))
                .setMaxLines(2)
                .build(),
        )
        .apply {
            if (subtitle.isNotBlank()) {
                setSecondaryLabelTextContent(
                    Text.Builder(context, subtitle)
                        .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                        .setColor(argb(colors.primary))
                        .setMaxLines(1)
                        .build(),
                )
            }
        }
        .build()

    // Wrap so the whole tile is tappable (EdgeContentLayout doesn't take modifiers itself).
    return LayoutElementBuilders.Box.Builder()
        .setWidth(expand())
        .setHeight(expand())
        .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(openAppClickable(context)).build())
        .addContent(content)
        .build()
}
