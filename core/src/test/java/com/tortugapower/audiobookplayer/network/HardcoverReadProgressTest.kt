package com.tortugapower.audiobookplayer.network

import com.google.gson.Gson
import com.tortugapower.audiobookplayer.network.HardcoverReadProgress.Outcome
import com.tortugapower.audiobookplayer.network.HardcoverReadProgress.ProgressUnit
import com.tortugapower.audiobookplayer.network.HardcoverReadProgress.Skip
import com.tortugapower.audiobookplayer.network.ReadInProgressData.Edition
import com.tortugapower.audiobookplayer.network.ReadInProgressData.Read
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** iOS `HardcoverReadProgressTests` + `HardcoverProgressScheduleTests`. */
class HardcoverReadProgressTest {

    private val audiobook = Edition(id = 1, pages = null, audioSeconds = 36_000, readingFormatId = 2)
    private val print = Edition(id = 2, pages = 300, audioSeconds = null, readingFormatId = 1)
    private val printWithoutPages = Edition(id = 3, pages = null, audioSeconds = null, readingFormatId = 1)

    private fun read(edition: Edition?, seconds: Int? = null, pages: Int? = null) =
        Read(id = 9, progressPages = pages, progressSeconds = seconds, edition = edition)

    private fun sent(outcome: Outcome) = (outcome as Outcome.Send).progress

    @Test fun `25 percent of a 36000 s audiobook is 9000 s`() {
        assertEquals(HardcoverReadProgress(ProgressUnit.SECONDS, 9_000, null), sent(HardcoverReadProgress.evaluate(25.0, read(audiobook))))
    }

    @Test fun `50 percent of 300 pages is 150`() {
        assertEquals(HardcoverReadProgress(ProgressUnit.PAGES, 150, null), sent(HardcoverReadProgress.evaluate(50.0, read(print))))
    }

    @Test fun `the format decides the unit, and without one a recording length means audiobook`() {
        val both = Edition(id = 4, pages = 300, audioSeconds = 36_000, readingFormatId = 4) // ebook
        assertEquals(ProgressUnit.PAGES, sent(HardcoverReadProgress.evaluate(50.0, read(both))).unit)
        val unformatted = Edition(id = 5, pages = 300, audioSeconds = 36_000, readingFormatId = null)
        assertEquals(ProgressUnit.SECONDS, sent(HardcoverReadProgress.evaluate(50.0, read(unformatted))).unit)
    }

    @Test fun `a read with no edition takes the fallback and sends its edition_id`() {
        val progress = sent(HardcoverReadProgress.evaluate(50.0, read(null), listOf(null, audiobook)))
        assertEquals(HardcoverReadProgress(ProgressUnit.SECONDS, 18_000, editionId = 1), progress)
        assertEquals(mapOf("progress_seconds" to 18_000, "edition_id" to 1), progress.objectFields)
    }

    @Test fun `a read's edition with a length is kept, one without is replaced`() {
        assertNull(sent(HardcoverReadProgress.evaluate(50.0, read(print), listOf(audiobook))).editionId)
        assertEquals(1, sent(HardcoverReadProgress.evaluate(50.0, read(printWithoutPages), listOf(audiobook))).editionId)
    }

    @Test fun `nothing at 0 or 100 percent`() {
        assertEquals(Outcome.Skipped(Skip.OutOfRange), HardcoverReadProgress.evaluate(0.0, read(audiobook)))
        assertEquals(Outcome.Skipped(Skip.OutOfRange), HardcoverReadProgress.evaluate(100.0, read(audiobook)))
    }

    @Test fun `nothing when no edition has a length`() {
        assertEquals(Outcome.Skipped(Skip.NoLength), HardcoverReadProgress.evaluate(50.0, read(printWithoutPages), listOf(null)))
    }

    @Test fun `nothing when not ahead of the read's progress`() {
        assertEquals(
            Outcome.Skipped(Skip.NotAhead(current = 20_000, value = 18_000)),
            HardcoverReadProgress.evaluate(50.0, read(audiobook, seconds = 20_000))
        )
    }

    @Test fun `a replaced edition's progress doesn't count`() {
        // 150 pages on an edition without a length are in another unit: the audiobook starts from 0.
        val progress = sent(HardcoverReadProgress.evaluate(10.0, read(printWithoutPages, pages = 150), listOf(audiobook)))
        assertEquals(3_600, progress.value)
    }

    @Test fun `kept short of the end - 99,99 percent of 1000 s is 999`() {
        val edition = Edition(id = 6, pages = null, audioSeconds = 1_000, readingFormatId = 2)
        assertEquals(999, sent(HardcoverReadProgress.evaluate(99.99, read(edition))).value)
        assertEquals(999, sent(HardcoverReadProgress.evaluate(99.999, read(edition))).value)
    }

    @Test fun `the read in progress answer decodes`() {
        val json = """
            {"user_books":[{"id":5,"status_id":2,"edition":null,
              "book":{"default_audio_edition":{"id":1,"pages":null,"audio_seconds":36000,"reading_format_id":2},
                      "default_ebook_edition":null,"default_physical_edition":null,
                      "audio_editions":[],"paged_editions":[{"id":2,"pages":300,"audio_seconds":null,"reading_format_id":1}]},
              "user_book_reads":[{"id":9,"progress_pages":null,"progress_seconds":120,"edition":null}]}]}
        """
        val data = Gson().fromJson(json, ReadInProgressData::class.java)
        val userBook = data.userBooks!!.single()
        assertEquals(2, userBook.statusId)
        assertEquals(120, userBook.userBookReads!!.single().progressSeconds)
        assertEquals(listOf(audiobook, null, null, null, print), userBook.book!!.defaultEditions)
    }

    // --- Schedule ---

    @Test fun `the first update goes at once, later ones within 120 s are held and only the latest sent`() {
        val schedule = HardcoverProgressSchedule(120_000)
        assertEquals(10.0, schedule.record(10.0, "a", now = 0))
        assertNull(schedule.record(11.0, "a", now = 10_000))
        assertNull(schedule.record(12.0, "a", now = 20_000))
        assertEquals(13.0, schedule.record(13.0, "a", now = 120_000))
        assertEquals(emptyList<Pair<String, Double>>(), schedule.flush(now = 121_000))
    }

    @Test fun `books are scheduled independently`() {
        val schedule = HardcoverProgressSchedule(120_000)
        assertEquals(10.0, schedule.record(10.0, "a", now = 0))
        assertEquals(50.0, schedule.record(50.0, "b", now = 1_000))
    }

    @Test fun `flush returns the latest held and restarts the interval`() {
        val schedule = HardcoverProgressSchedule(120_000)
        schedule.record(10.0, "a", now = 0)
        schedule.record(12.0, "a", now = 30_000)
        assertEquals(listOf("a" to 12.0), schedule.flush(now = 60_000))
        assertNull(schedule.record(13.0, "a", now = 150_000))
        assertEquals(14.0, schedule.record(14.0, "a", now = 180_000))
    }
}
