package com.tortugapower.audiobookplayer.network

import com.google.gson.annotations.SerializedName

/**
 * The read in progress of a book in the user's library (iOS `HardcoverReadInProgressData`): the started read that isn't
 * finished, where progress goes, with its edition, which progress is measured against.
 */
data class ReadInProgressData(
    @SerializedName("user_books") val userBooks: List<UserBook>?
) {
    data class UserBook(
        @SerializedName("id") val id: Int,
        @SerializedName("status_id") val statusId: Int?,
        @SerializedName("user_book_reads") val userBookReads: List<Read>?
    )

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
) {
    enum class ProgressUnit { SECONDS, PAGES }

    val objectFields: Map<String, Int>
        get() = mapOf((if (unit == ProgressUnit.SECONDS) "progress_seconds" else "progress_pages") to value)

    /** Why nothing is sent, for the log. */
    sealed class Skip {
        data object OutOfRange : Skip()
        /**
         * The read has no edition, or its edition has no length in its unit. No other edition is picked for it: the
         * edition on a user's read is theirs, and BookPlayer only chooses one when it adds the book (iOS).
         */
        data object NoLength : Skip()
        data class NotAhead(val current: Int, val value: Int) : Skip()
    }

    sealed class Outcome {
        data class Send(val progress: HardcoverReadProgress) : Outcome()
        data class Skipped(val reason: Skip) : Outcome()
    }

    companion object {
        /** Between the start and the end, exclusive: the only progress ever sent. */
        fun isInRange(percent: Double): Boolean = percent > 0 && percent < 100

        const val AUDIOBOOK_FORMAT = 2

        /**
         * Measured against the read's own edition. Nothing when it has no length, or when the progress isn't ahead of
         * what Hardcover has (a book listened to again from earlier, or another device ahead). Kept short of the end:
         * reaching it finishes the read, and finishing is the Read status, set when the book is marked finished.
         */
        fun evaluate(percent: Double, read: ReadInProgressData.Read): Outcome {
            if (!isInRange(percent)) return Outcome.Skipped(Skip.OutOfRange)

            val (unit, total) = read.edition?.let(::measure) ?: return Outcome.Skipped(Skip.NoLength)
            val current = (if (unit == ProgressUnit.SECONDS) read.progressSeconds else read.progressPages) ?: 0
            val value = minOf((total.toDouble() * percent / 100).toInt(), total - 1)
            if (value <= current) return Outcome.Skipped(Skip.NotAhead(current, value))

            return Outcome.Send(HardcoverReadProgress(unit, value))
        }

        /**
         * The edition's unit and length: seconds for an audiobook, pages otherwise (as Hardcover's SDK does); an
         * edition without a format counts as an audiobook when it has a recording length. "Has a length" = above 1.
         */
        private fun measure(edition: ReadInProgressData.Edition): Pair<ProgressUnit, Int>? {
            val isAudiobook = edition.readingFormatId?.let { it == AUDIOBOOK_FORMAT } ?: ((edition.audioSeconds ?: 0) > 0)
            val total = (if (isAudiobook) edition.audioSeconds else edition.pages) ?: return null
            if (total <= 1) return null
            return (if (isAudiobook) ProgressUnit.SECONDS else ProgressUnit.PAGES) to total
        }
    }
}
