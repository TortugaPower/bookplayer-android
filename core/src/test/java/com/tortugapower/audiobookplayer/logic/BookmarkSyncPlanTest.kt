package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.entities.BookmarkEntity
import com.tortugapower.audiobookplayer.database.entities.BookmarkType
import com.tortugapower.audiobookplayer.model.SyncableBookmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BookmarkSync.plan]: how `GET /v1/library/bookmarks` rows merge into the local table. The server
 * stores whole seconds (set_bookmark rounds on upload) while local rows keep their fraction, so a
 * match is on rounded time; the server note wins on a match; nothing local is deleted.
 */
class BookmarkSyncPlanTest {

    private fun local(id: Long, time: Double, note: String? = null, type: BookmarkType = BookmarkType.USER) =
        BookmarkEntity(id = id, bookUuid = "book", time = time, note = note, type = type)

    private fun remote(time: Double, note: String? = null) =
        SyncableBookmark(key = "book.mp3", time = time, note = note, uuid = "book")

    @Test fun `a server row with no local match is inserted as a user bookmark`() {
        val plan = BookmarkSync.plan("book", emptyList(), listOf(remote(120.0, "Great line")))

        assertEquals(1, plan.toInsert.size)
        assertEquals("book", plan.toInsert[0].bookUuid)
        assertEquals(120.0, plan.toInsert[0].time, 0.0)
        assertEquals("Great line", plan.toInsert[0].note)
        assertEquals(BookmarkType.USER, plan.toInsert[0].type)
        assertTrue(plan.toUpdate.isEmpty())
    }

    @Test fun `a local bookmark within the same second is the same bookmark`() {
        val plan = BookmarkSync.plan("book", listOf(local(1, 120.4)), listOf(remote(120.0)))

        assertTrue(plan.toInsert.isEmpty())
        assertTrue(plan.toUpdate.isEmpty())
    }

    @Test fun `the server note wins on a match and keeps the local id and time`() {
        val plan = BookmarkSync.plan("book", listOf(local(7, 120.4, note = "old")), listOf(remote(120.0, "new")))

        assertTrue(plan.toInsert.isEmpty())
        assertEquals(1, plan.toUpdate.size)
        assertEquals(7L, plan.toUpdate[0].id)
        assertEquals(120.4, plan.toUpdate[0].time, 0.0)
        assertEquals("new", plan.toUpdate[0].note)
    }

    @Test fun `a blank server note means no note`() {
        val plan = BookmarkSync.plan("book", listOf(local(7, 120.0, note = "old")), listOf(remote(120.0, "")))

        assertEquals(1, plan.toUpdate.size)
        assertNull(plan.toUpdate[0].note)
        // ...and a blank note on both sides is not a change.
        assertTrue(BookmarkSync.plan("book", listOf(local(7, 120.0, note = "")), listOf(remote(120.0, null))).toUpdate.isEmpty())
    }

    @Test fun `automatic bookmarks never match server rows`() {
        val plan = BookmarkSync.plan("book", listOf(local(1, 120.0, type = BookmarkType.PLAY)), listOf(remote(120.0)))

        assertEquals(1, plan.toInsert.size)
        assertTrue(plan.toUpdate.isEmpty())
    }

    @Test fun `local-only bookmarks are left alone and duplicate server seconds collapse`() {
        val plan = BookmarkSync.plan(
            "book",
            listOf(local(1, 30.0, note = "mine")),
            listOf(remote(60.0), remote(60.4))
        )

        assertEquals(1, plan.toInsert.size)
        assertEquals(60.0, plan.toInsert[0].time, 0.0)
        assertTrue(plan.toUpdate.isEmpty())
    }
}
