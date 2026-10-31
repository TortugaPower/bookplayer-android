package com.tortugapower.audiobookplayer.network

import com.google.gson.annotations.SerializedName

/**
 * The read in progress of a book in the user's library (iOS `ReadInProgressData`): the started read that isn't
 * finished, where progress goes, with the editions its length can come from.
 */
data class ReadInProgressData(
    @SerializedName("user_books") val userBooks: List<UserBook>?
) {
    data class UserBook(
        @SerializedName("id") val id: Int,
        @SerializedName("status_id") val statusId: Int?,
        /** The library entry's edition, which a read without its own goes by. */
        @SerializedName("edition") val edition: Edition?,
        @SerializedName("book") val book: Book?,
        @SerializedName("user_book_reads") val userBookReads: List<Read>?
    )

    data class Book(
        @SerializedName("default_audio_edition") val defaultAudioEdition: Edition?,
        @SerializedName("default_ebook_edition") val defaultEbookEdition: Edition?,
        @SerializedName("default_physical_edition") val defaultPhysicalEdition: Edition?,
        /** The most read audiobook edition with a duration, for books whose defaults have no length. */
        @SerializedName("audio_editions") val audioEditions: List<Edition>?,
        /** The most read edition with a page count. */
        @SerializedName("paged_editions") val pagedEditions: List<Edition>?
    ) {
        /**
         * Audiobook first, this being an audiobook app; ebook and print ones count in pages. The defaults, then the
         * most read editions with a length: a book's defaults can have none.
         */
        val defaultEditions: List<Edition?>
            get() = listOf(
                defaultAudioEdition,
                defaultEbookEdition,
                defaultPhysicalEdition,
                audioEditions?.firstOrNull(),
                pagedEditions?.firstOrNull(),
            )
    }

    data class Read(
        @SerializedName("id") val id: Int,
        @SerializedName("progress_pages") val progressPages: Int?,
        @SerializedName("progress_seconds") val progressSeconds: Int?,
        @SerializedName("edition") val edition: Edition?
    )

    data class Edition(
        @SerializedName("id") val id: Int,
        @SerializedName("pages") val pages: Int?,
        @SerializedName("audio_seconds") val audioSeconds: Int?,
        /** 1 physical, 2 audiobook, 4 ebook. */
        @SerializedName("reading_format_id") val readingFormatId: Int?
    ) {
        fun describe(): String =
            "#$id format ${readingFormatId ?: "?"} pages ${pages ?: "-"} seconds ${audioSeconds ?: "-"}"
    }
}

/**
 * What a book's progress writes on Hardcover's read (`DatesReadInput`): seconds of an audiobook edition, else pages.
 * Hardcover's edition can be another recording than the file, so the percentage is carried over, never the position.
 */
data class HardcoverReadProgress(
    val unit: ProgressUnit,
    val value: Int,
    /**
     * The edition measured against, when it isn't the read's own: written with the progress, as Hardcover needs the
     * read's edition to show it. Reads started by a status change have none.
     */
    val editionId: Int?,
) {
    enum class ProgressUnit { SECONDS, PAGES }

    val objectFields: Map<String, Int>
        get() = buildMap {
            put(if (unit == ProgressUnit.SECONDS) "progress_seconds" else "progress_pages", value)
            editionId?.let { put("edition_id", it) }
        }

    /** Why nothing is sent, for the log. */
    sealed class Skip {
        data object OutOfRange : Skip()
        /** No edition with a length in its unit, the read's own nor any other. */
        data object NoLength : Skip()
        data class NotAhead(val current: Int, val value: Int) : Skip()
    }

    sealed class Outcome {
        data class Send(val progress: HardcoverReadProgress) : Outcome()
        data class Skipped(val reason: Skip) : Outcome()
    }

    companion object {
        const val AUDIOBOOK_FORMAT = 2

        /**
         * Measured against the read's edition when it has a length. Hardcover gives a read the book's default print
         * edition by itself, which may have none: then, as with a read without one, the first of [fallbacks] with a
         * length (the library entry's edition, then the book's defaults), written on the read with the progress.
         * Nothing when the progress isn't ahead of what Hardcover has (a book listened to again from earlier, or
         * another device ahead). Kept short of the end: reaching it finishes the read, and finishing is the Read
         * status, set when the book is marked finished.
         */
        fun evaluate(
            percent: Double,
            read: ReadInProgressData.Read,
            fallbacks: List<ReadInProgressData.Edition?> = emptyList(),
        ): Outcome {
            if (!(percent > 0 && percent < 100)) return Outcome.Skipped(Skip.OutOfRange)

            val measured = (listOf(read.edition) + fallbacks).filterNotNull().firstNotNullOfOrNull(::measure)
                ?: return Outcome.Skipped(Skip.NoLength)
            val (edition, unit, total) = measured

            // Progress already on the read counts only in the edition it was recorded against.
            val keepsEdition = edition.id == read.edition?.id
            val recorded = if (keepsEdition) {
                if (unit == ProgressUnit.SECONDS) read.progressSeconds else read.progressPages
            } else null
            val current = recorded ?: 0
            val value = minOf((total.toDouble() * percent / 100).toInt(), total - 1)
            if (value <= current) return Outcome.Skipped(Skip.NotAhead(current, value))

            return Outcome.Send(HardcoverReadProgress(unit, value, if (keepsEdition) null else edition.id))
        }

        /**
         * The edition's unit and length: seconds for an audiobook, pages otherwise (as Hardcover's SDK does); an
         * edition without a format counts as an audiobook when it has a recording length. "Has a length" = above 1.
         */
        private fun measure(edition: ReadInProgressData.Edition): Triple<ReadInProgressData.Edition, ProgressUnit, Int>? {
            val isAudiobook = edition.readingFormatId?.let { it == AUDIOBOOK_FORMAT } ?: (edition.audioSeconds != null)
            val total = (if (isAudiobook) edition.audioSeconds else edition.pages) ?: return null
            if (total <= 1) return null
            return Triple(edition, if (isAudiobook) ProgressUnit.SECONDS else ProgressUnit.PAGES, total)
        }
    }
}

/**
 * When to send a book's progress (iOS `HardcoverProgressSchedule`): its first update right away, then at most once
 * per [intervalMs], with the latest one held until then or until playback pauses. Not thread-safe: the owner
 * serializes access.
 */
class HardcoverProgressSchedule(private val intervalMs: Long) {
    private val lastSent = mutableMapOf<String, Long>()
    private val pending = mutableMapOf<String, Double>()

    /** The percentage to send now, if any; otherwise it's kept for later. */
    fun record(percent: Double, key: String, now: Long): Double? {
        val last = lastSent[key]
        if (last != null && now - last < intervalMs) {
            pending[key] = percent
            return null
        }
        lastSent[key] = now
        pending.remove(key)
        return percent
    }

    /** Everything held back, to send now; each one's interval restarts. */
    fun flush(now: Long): List<Pair<String, Double>> {
        val held = pending.toList()
        pending.clear()
        held.forEach { (key, _) -> lastSent[key] = now }
        return held
    }
}
