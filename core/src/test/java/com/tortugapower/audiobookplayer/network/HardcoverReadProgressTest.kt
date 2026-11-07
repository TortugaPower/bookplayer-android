package com.tortugapower.audiobookplayer.network

import com.google.gson.Gson
import com.tortugapower.audiobookplayer.network.HardcoverReadProgress.Outcome
import com.tortugapower.audiobookplayer.network.HardcoverReadProgress.ProgressUnit
import com.tortugapower.audiobookplayer.network.HardcoverReadProgress.Skip
import com.tortugapower.audiobookplayer.network.ReadInProgressData.Edition
import com.tortugapower.audiobookplayer.network.ReadInProgressData.Read
import org.junit.Assert.assertEquals
import org.junit.Test

/** iOS `HardcoverReadProgressTests` (the schedule became the Hardcover lane: `HardcoverProgressSyncTest`). */
class HardcoverReadProgressTest {

    private val audiobook = Edition(id = 1, pages = null, audioSeconds = 36_000, readingFormatId = 2)
    private val print = Edition(id = 2, pages = 300, audioSeconds = null, readingFormatId = 1)
    private val printWithoutPages = Edition(id = 3, pages = null, audioSeconds = null, readingFormatId = 1)

    private fun read(edition: Edition?, seconds: Int? = null, pages: Int? = null) =
        Read(id = 9, progressPages = pages, progressSeconds = seconds, edition = edition)

    private fun sent(outcome: Outcome) = (outcome as Outcome.Send).progress

    @Test fun `25 percent of a 36000 s audiobook is 9000 s`() {
        assertEquals(HardcoverReadProgress(ProgressUnit.SECONDS, 9_000), sent(HardcoverReadProgress.evaluate(25.0, read(audiobook))))
    }

    @Test fun `50 percent of 300 pages is 150`() {
        assertEquals(HardcoverReadProgress(ProgressUnit.PAGES, 150), sent(HardcoverReadProgress.evaluate(50.0, read(print))))
    }

    @Test fun `the format decides the unit, and without one a recording length means audiobook`() {
        val both = Edition(id = 4, pages = 300, audioSeconds = 36_000, readingFormatId = 4) // ebook
        assertEquals(ProgressUnit.PAGES, sent(HardcoverReadProgress.evaluate(50.0, read(both))).unit)
        val unformatted = Edition(id = 5, pages = 300, audioSeconds = 36_000, readingFormatId = null)
        assertEquals(ProgressUnit.SECONDS, sent(HardcoverReadProgress.evaluate(50.0, read(unformatted))).unit)
    }

    @Test fun `the unit's field is all that's written`() {
        assertEquals(mapOf("progress_seconds" to 18_000), sent(HardcoverReadProgress.evaluate(50.0, read(audiobook))).objectFields)
        assertEquals(mapOf("progress_pages" to 150), sent(HardcoverReadProgress.evaluate(50.0, read(print))).objectFields)
    }

    @Test fun `no other edition stands in for the read's (iOS)`() {
        assertEquals(Outcome.Skipped(Skip.NoLength), HardcoverReadProgress.evaluate(50.0, read(null)))
        assertEquals(Outcome.Skipped(Skip.NoLength), HardcoverReadProgress.evaluate(50.0, read(printWithoutPages)))
    }

    @Test fun `an edition without a format and no recording length counts in pages`() {
        val noAudio = Edition(id = 7, pages = 300, audioSeconds = 0, readingFormatId = null)
        assertEquals(HardcoverReadProgress(ProgressUnit.PAGES, 150), sent(HardcoverReadProgress.evaluate(50.0, read(noAudio))))
    }

    @Test fun `nothing at 0 or 100 percent`() {
        assertEquals(Outcome.Skipped(Skip.OutOfRange), HardcoverReadProgress.evaluate(0.0, read(audiobook)))
        assertEquals(Outcome.Skipped(Skip.OutOfRange), HardcoverReadProgress.evaluate(100.0, read(audiobook)))
    }

    @Test fun `nothing when not ahead of the read's progress`() {
        assertEquals(
            Outcome.Skipped(Skip.NotAhead(current = 20_000, value = 18_000)),
            HardcoverReadProgress.evaluate(50.0, read(audiobook, seconds = 20_000))
        )
    }

    @Test fun `kept short of the end - 99,99 percent of 1000 s is 999`() {
        val edition = Edition(id = 6, pages = null, audioSeconds = 1_000, readingFormatId = 2)
        assertEquals(999, sent(HardcoverReadProgress.evaluate(99.99, read(edition))).value)
        assertEquals(999, sent(HardcoverReadProgress.evaluate(99.999, read(edition))).value)
    }

    @Test fun `the read in progress answer decodes`() {
        val json = """
            {"user_books":[{"id":5,"status_id":2,
              "user_book_reads":[{"id":9,"progress_pages":null,"progress_seconds":120,
                "edition":{"id":1,"pages":null,"audio_seconds":36000,"reading_format_id":2}}]}]}
        """
        val data = Gson().fromJson(json, ReadInProgressData::class.java)
        val userBook = data.userBooks!!.single()
        assertEquals(2, userBook.statusId)
        assertEquals(120, userBook.userBookReads!!.single().progressSeconds)
        assertEquals(audiobook, userBook.userBookReads!!.single().edition)
    }
}
