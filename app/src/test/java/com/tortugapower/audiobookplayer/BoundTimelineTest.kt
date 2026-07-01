package com.tortugapower.audiobookplayer

import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.logic.BoundTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [BoundTimeline] — the per-item <-> whole-book conversion for BOUND books. Pure JVM
 * (no Android / Media3), so the conversion math is verified once here instead of being re-derived
 * across PlaybackManager / PlayerViewModel / PlayerScreen.
 */
class BoundTimelineTest {

    private fun book(uuid: String, duration: Double, type: ItemType = ItemType.BOOK) =
        LibraryItemEntity(uuid = uuid, title = uuid, duration = duration, type = type)

    // Three sub-books: 100s, 200s, 50s -> chapter starts 0, 100, 300; total 350s.
    private fun sample() = BoundTimeline.fromSubBooks(
        bookUuid = "vol",
        subItems = listOf(book("a", 100.0), book("b", 200.0), book("c", 50.0))
    )

    @Test
    fun fromSubBooks_buildsCumulativeStarts_andTotalDuration() {
        val t = sample()
        assertEquals(3, t.chapters.size)
        assertEquals(0.0, t.chapters[0].start, 0.0001)
        assertEquals(100.0, t.chapters[1].start, 0.0001)
        assertEquals(300.0, t.chapters[2].start, 0.0001)
        assertEquals(350_000L, t.totalDurationMs)
    }

    @Test
    fun fromSubBooks_filtersOutNonBookChildren() {
        // A stray FOLDER child must NOT become a chapter, or chapter indices drift off the playlist.
        val t = BoundTimeline.fromSubBooks(
            bookUuid = "vol",
            subItems = listOf(
                book("a", 100.0),
                book("nested", 999.0, type = ItemType.FOLDER),
                book("b", 200.0)
            )
        )
        assertEquals(2, t.chapters.size)
        assertEquals(listOf("a", "b"), t.chapters.map { it.title })
        assertEquals(100.0, t.chapters[1].start, 0.0001)
    }

    @Test
    fun indexAt_locatesChapterByWholeBookPosition() {
        val t = sample()
        assertEquals(0, t.indexAt(0L))
        assertEquals(0, t.indexAt(99_000L))
        assertEquals(1, t.indexAt(100_000L)) // exact boundary belongs to the next chapter
        assertEquals(1, t.indexAt(299_000L))
        assertEquals(2, t.indexAt(300_000L))
        assertEquals(2, t.indexAt(349_000L))
    }

    @Test
    fun indexAt_clampsOutOfRange() {
        val t = sample()
        assertEquals(0, t.indexAt(-5_000L))      // before the start -> first chapter
        assertEquals(2, t.indexAt(350_000L))     // exactly at the end -> last chapter
        assertEquals(2, t.indexAt(999_000L))     // past the end -> last chapter
    }

    @Test
    fun indexAt_emptyTimelineReturnsMinusOne() {
        val empty = BoundTimeline.of(emptyList())
        assertEquals(-1, empty.indexAt(0L))
        assertTrue(empty.isEmpty)
        assertEquals(0L, empty.totalDurationMs)
    }

    @Test
    fun toAbsoluteMs_addsChapterStart() {
        val t = sample()
        assertEquals(40_000L, t.toAbsoluteMs(0, 40_000L))   // 0s start + 40s
        assertEquals(150_000L, t.toAbsoluteMs(1, 50_000L))  // 100s start + 50s
        assertEquals(310_000L, t.toAbsoluteMs(2, 10_000L))  // 300s start + 10s
    }

    @Test
    fun toAbsoluteMs_outOfRangeFallsBackToRaw() {
        val t = sample()
        assertEquals(7_000L, t.toAbsoluteMs(9, 7_000L))
    }

    @Test
    fun toLocal_splitsIntoChapterIndexAndOffset() {
        val t = sample()
        assertEquals(BoundTimeline.PlayerPosition(0, 40_000L), t.toLocal(40_000L))
        assertEquals(BoundTimeline.PlayerPosition(1, 50_000L), t.toLocal(150_000L))
        assertEquals(BoundTimeline.PlayerPosition(2, 10_000L), t.toLocal(310_000L))
    }

    @Test
    fun toLocal_toAbsolute_roundTrips() {
        val t = sample()
        for (absMs in listOf(0L, 1_000L, 99_999L, 100_000L, 250_000L, 349_999L)) {
            val local = t.toLocal(absMs)
            assertEquals(absMs, t.toAbsoluteMs(local.mediaItemIndex, local.positionMs))
        }
    }
}
