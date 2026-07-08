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
import com.google.common.util.concurrent.ListenableFuture
import com.tortugapower.audiobookplayer.wear.R
import com.tortugapower.audiobookplayer.wear.glance.GlanceState
import com.tortugapower.audiobookplayer.wear.glance.NowPlayingGlance
import com.tortugapower.audiobookplayer.wear.presentation.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future

/**
 * A glanceable "now playing" [TileService]: shows the current/last-played book (title + author) with a
 * whole-book progress arc; tapping the tile opens the app (now-playing). Data comes from the shared
 * [NowPlayingGlance]; the layout is a pure function of [GlanceState].
 */
class NowPlayingTileService : TileService() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> = scope.future {
        val layout = tileLayout(applicationContext, requestParams.deviceConfiguration, NowPlayingGlance.resolve(applicationContext))
        TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
            .build()
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
private fun tileLayout(context: Context, deviceParams: DeviceParameters, state: GlanceState): LayoutElement {
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

    // Wrap so the whole tile is tappable (EdgeContentLayout doesn't take modifiers itself). Semantics so
    // TalkBack announces the tap action (this is an audiobook app with many low-vision users).
    return LayoutElementBuilders.Box.Builder()
        .setWidth(expand())
        .setHeight(expand())
        .setModifiers(
            ModifiersBuilders.Modifiers.Builder()
                .setClickable(openAppClickable(context))
                .setSemantics(
                    ModifiersBuilders.Semantics.Builder()
                        .setContentDescription(context.getString(R.string.wear_tile_open))
                        .build(),
                )
                .build(),
        )
        .addContent(content)
        .build()
}
