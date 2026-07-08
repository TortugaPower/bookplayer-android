package com.tortugapower.audiobookplayer.wear.complication

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.tortugapower.audiobookplayer.wear.R
import com.tortugapower.audiobookplayer.wear.glance.GlanceState
import com.tortugapower.audiobookplayer.wear.glance.NowPlayingGlance
import com.tortugapower.audiobookplayer.wear.presentation.MainActivity

/**
 * A watch-face complication for the current/last-played book (iOS `ComplicationController` parity), sharing
 * the mode-aware [NowPlayingGlance] with the tile. Every type carries the book glyph so it's recognizably
 * ours, and taps through to now-playing:
 *  - RANGED_VALUE (corner/circular gauge): progress gauge + book icon + the chapter stacked on two lines —
 *    the number (larger `text`) over a "CHAP" label (smaller `title`), when known. A book title never fits
 *    these slots; a non-PRO/no-progress state is just the icon + an empty gauge.
 *  - SHORT_TEXT (small rectangle): "CHAP N" when known, else the app name + icon.
 *  - LONG_TEXT (large rectangle): "CHAP N"/"Last Played" header + the book title + icon (iOS rectangular).
 *  - MONOCHROMATIC_IMAGE: the book icon.
 *
 * Updates are pushed on playback change (WearApp's `ComplicationDataSourceUpdateRequester`), not polled.
 */
class NowPlayingComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        complicationData(
            type,
            GlanceState("The Sea of Monsters", "Rick Riordan", progress = 0.5f, hasItem = true, chapterNumber = 3),
        )

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        complicationData(request.complicationType, NowPlayingGlance.resolve(this))

    private fun complicationData(type: ComplicationType, state: GlanceState): ComplicationData? {
        val tap = openNowPlayingIntent()
        val icon = MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_bookplayer_glyph)).build()
        // "CHAP N" when the live item's chapter is known; otherwise the generic "Last Played".
        val chapterText: ComplicationText? = state.chapterNumber
            ?.let { PlainComplicationText.Builder(getString(R.string.wear_complication_chapter, it)).build() }
        // Two-line split for the circular gauge: the number (larger, as `text`) over a "CHAP" label (smaller,
        // as `title`), so the chapter reads clearly in the tiny gauge slot.
        val chapterNumberText: ComplicationText? = state.chapterNumber
            ?.let { PlainComplicationText.Builder(getString(R.string.wear_complication_chapter_number, it)).build() }
        val chapterLabelText = PlainComplicationText.Builder(getString(R.string.wear_complication_chapter_label)).build()
        val lastPlayed = PlainComplicationText.Builder(getString(R.string.wear_complication_last_played)).build()
        val label = chapterText ?: lastPlayed
        // SHORT_TEXT is a single tiny slot: show "CHAP N" when known, else the app name (the "Last Played"
        // picker label truncates to "LAST P…" and reads as a different thing there). The icon already brands it.
        val shortText = chapterText ?: PlainComplicationText.Builder(getString(R.string.app_name)).build()
        val bookTitle = PlainComplicationText.Builder(
            if (state.hasItem) state.title else getString(R.string.wear_complication_last_played),
        ).build()
        return when (type) {
            ComplicationType.RANGED_VALUE ->
                RangedValueComplicationData.Builder(
                    value = state.progress,
                    min = 0f,
                    max = 1f,
                    contentDescription = bookTitle,
                )
                    .setMonochromaticImage(icon)
                    // Stack the chapter as number (text) + "CHAP" (title); both only when the chapter is known.
                    .apply {
                        chapterNumberText?.let {
                            setText(it)
                            setTitle(chapterLabelText)
                        }
                    }
                    .setTapAction(tap)
                    .build()

            ComplicationType.SHORT_TEXT ->
                ShortTextComplicationData.Builder(text = shortText, contentDescription = bookTitle)
                    .setMonochromaticImage(icon)
                    .setTapAction(tap)
                    .build()

            ComplicationType.LONG_TEXT ->
                LongTextComplicationData.Builder(text = bookTitle, contentDescription = bookTitle)
                    .setTitle(label)
                    .setMonochromaticImage(icon)
                    .setTapAction(tap)
                    .build()

            ComplicationType.MONOCHROMATIC_IMAGE ->
                MonochromaticImageComplicationData.Builder(monochromaticImage = icon, contentDescription = bookTitle)
                    .setTapAction(tap)
                    .build()

            else -> null
        }
    }

    private fun openNowPlayingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
