package com.tortugapower.audiobookplayer.logic

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `/uuids` requests stay within the API's record limit and its 100 KB body limit, as Gson writes them */
class MatchUuidsBatchingTest {

    private fun wireBytes(batch: Map<String, String>) =
        Gson().toJson(mapOf("items" to batch)).toByteArray(Charsets.UTF_8).size

    private fun uuid(i: Int) = "00000000-0000-4000-8000-%012d".format(i)

    @Test fun shortPaths_areCappedByCount() {
        val items = (0 until 2_500).associate { "Book $it.m4b" to uuid(it) }

        val batches = MatchUuidsBatching.batches(items)

        assertEquals(listOf(1_000, 1_000, 500), batches.map { it.size })
        assertEquals(items.keys.toList(), batches.flatMap { it.keys })
    }

    /** Long, escaped and non-ASCII paths reach the byte limit long before 1,000 entries */
    @Test fun longPaths_areCappedByTheirBytesOnTheWire() {
        val items = (0 until 1_000).associate {
            "Ünïcödé 書籍 <Series> & 'Volume' $it/" + "Chapter = long name ".repeat(6) + "$it.m4b" to uuid(it)
        }

        val batches = MatchUuidsBatching.batches(items)

        assertTrue(batches.size > 1)
        batches.forEach { assertTrue(wireBytes(it) <= MatchUuidsBatching.MAX_BODY_BYTES) }
        // Full batches: the next entry would not have fit, so the count is exact, not over-cautious
        batches.zipWithNext { batch, next ->
            val nextEntry = next.entries.first()
            assertTrue(wireBytes(batch + (nextEntry.key to nextEntry.value)) > MatchUuidsBatching.MAX_BODY_BYTES)
        }
        assertEquals(items.keys.toList(), batches.flatMap { it.keys })
    }

    @Test fun anEntryOverTheBudget_goesAloneAndDoesNotStopTheRest() {
        val huge = "x".repeat(200) + ".m4b"
        val items = linkedMapOf("a.m4b" to uuid(1), huge to uuid(2), "b.m4b" to uuid(3))

        val batches = MatchUuidsBatching.batches(items, maxBytes = 120)

        assertEquals(listOf(listOf("a.m4b"), listOf(huge), listOf("b.m4b")), batches.map { it.keys.toList() })
    }

    @Test fun nothingToSend_isNoBatch() {
        assertEquals(emptyList<Map<String, String>>(), MatchUuidsBatching.batches(emptyMap()))
    }
}
