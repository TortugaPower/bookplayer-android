package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity
import com.tortugapower.audiobookplayer.network.HardcoverReadProgress
import com.tortugapower.audiobookplayer.network.ReadInProgressData
import com.tortugapower.audiobookplayer.network.ReadInProgressData.Edition
import com.tortugapower.audiobookplayer.network.ReadInProgressData.Read
import com.tortugapower.audiobookplayer.network.ReadInProgressData.UserBook
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** The checks a progress update makes before it writes on Hardcover's read. */
class HardcoverProgressUpdateTest {

    private val audiobook = Edition(id = 1, pages = null, audioSeconds = 36_000, readingFormatId = 2)

    private var lacksScope = false
    private var link: ExternalResourceEntity? =
        ExternalResourceEntity(providerName = "hardcover", providerId = "77", syncStatus = HardcoverProgressUpdate.LINK_READING, libraryItemUuid = "u1")
    private var userId: String? = "42"
    private var userBooks: List<UserBook>? = listOf(userBook())
    private var refusal: String? = null
    private val asked = mutableListOf<Pair<Int, Int>>()
    private val written = mutableListOf<Pair<Int, HardcoverReadProgress>>()
    private var threshold = 0.2f
    /** What setting Currently Reading answers (the entry's id), or throws */
    private var startAnswer: () -> Int? = { 5 }
    private val started = mutableListOf<Int>()
    private val marked = mutableListOf<String>()

    private fun userBook(statusId: Int = 2, reads: List<Read> = listOf(Read(9, null, 3_600, audiobook))) =
        UserBook(id = 5, statusId = statusId, userBookReads = reads)

    private fun update(percent: Double = 25.0) = runBlocking {
        HardcoverProgressUpdate(
            lacksReadLibrary = { lacksScope },
            hardcoverLink = { link },
            readingThreshold = { threshold },
            startReading = { book -> started += book; startAnswer() },
            markReading = { uuid -> marked += uuid; link = link?.copy(syncStatus = HardcoverProgressUpdate.LINK_READING) },
            userId = { userId },
            readInProgress = { user, book -> asked += user to book; ReadInProgressData(userBooks) },
            updateReadProgress = { read, progress -> written += read to progress; refusal },
        ).update("u1", percent)
    }

    @Test fun `a Currently Reading book's progress is written on its read`() {
        assertEquals(HardcoverProgressResult.Sent, update(25.0))
        assertEquals(listOf(42 to 77), asked)
        assertEquals(listOf(9 to HardcoverReadProgress(HardcoverReadProgress.ProgressUnit.SECONDS, 9_000)), written)
        assertTrue("already Currently Reading: no status change", started.isEmpty() && marked.isEmpty())
    }

    @Test fun `past the threshold, a book not Currently Reading yet is set so first, then its progress is written (iOS)`() {
        link = link!!.copy(syncStatus = "library")
        assertEquals(HardcoverProgressResult.Sent, update(25.0))
        assertEquals(listOf(77), started)
        assertEquals(listOf("u1"), marked)
        assertEquals(1, written.size)
    }

    /** 1.4 set `reading` before sending the status and dropped a failed send: Hardcover may never have got it */
    @Test fun `a link 1_4 marked reading is sent Currently Reading once more, then marked as taken`() {
        link = link!!.copy(syncStatus = "reading")
        assertEquals(HardcoverProgressResult.Sent, update(25.0))
        assertEquals(listOf(77), started)
        assertEquals(HardcoverProgressUpdate.LINK_READING, link!!.syncStatus)

        assertEquals(HardcoverProgressResult.Sent, update(30.0))
        assertEquals("sent once", listOf(77), started)
    }

    @Test fun `below the threshold, a book not Currently Reading yet sends nothing`() {
        link = link!!.copy(syncStatus = "synced")
        assertEquals(HardcoverProgressResult.NotReading, update(15.0))
        assertTrue(started.isEmpty() && asked.isEmpty() && written.isEmpty())
    }

    @Test fun `a status change worth retrying throws before any progress, and the link isn't marked`() {
        link = link!!.copy(syncStatus = "library")
        startAnswer = { throw IOException("offline") }
        assertThrows(IOException::class.java) { update(25.0) }
        assertTrue(marked.isEmpty() && asked.isEmpty() && written.isEmpty())
        assertEquals("library", link!!.syncStatus)
    }

    @Test fun `a status change Hardcover answered with no entry sends no progress, and the next run tries it again`() {
        link = link!!.copy(syncStatus = "library")
        startAnswer = { null }
        assertEquals(HardcoverProgressResult.NoReadInProgress, update(25.0))
        assertTrue(marked.isEmpty() && written.isEmpty())

        startAnswer = { 5 }
        assertEquals(HardcoverProgressResult.Sent, update(26.0))
        assertEquals(listOf(77, 77), started)
    }

    @Test fun `a sign-in without read library sends nothing`() {
        lacksScope = true
        assertEquals(HardcoverProgressResult.MissingScope, update())
        assertTrue(asked.isEmpty() && written.isEmpty())
    }

    @Test fun `a book not linked, or finished, sends nothing`() {
        link = link!!.copy(syncStatus = "read")
        assertEquals(HardcoverProgressResult.NotReading, update())
        link = null
        assertEquals(HardcoverProgressResult.NotReading, update())
        assertTrue(started.isEmpty() && asked.isEmpty())
    }

    @Test fun `no account id sends nothing`() {
        userId = null
        assertEquals(HardcoverProgressResult.NoUserId, update())
        assertTrue(asked.isEmpty())
    }

    @Test fun `a book not Currently Reading on Hardcover, or without a read, is skipped`() {
        userBooks = listOf(userBook(statusId = 3))
        assertEquals(HardcoverProgressResult.NoReadInProgress, update())
        userBooks = listOf(userBook(reads = emptyList()))
        assertEquals(HardcoverProgressResult.NoReadInProgress, update())
        userBooks = emptyList()
        assertEquals(HardcoverProgressResult.NoReadInProgress, update())
        assertTrue(written.isEmpty())
    }

    @Test fun `progress behind Hardcover's is skipped`() {
        assertEquals(HardcoverProgressResult.Skipped(HardcoverReadProgress.Skip.NotAhead(3_600, 1_800)), update(5.0))
        assertTrue(written.isEmpty())
    }

    @Test fun `Hardcover refusing the write is reported`() {
        refusal = "nope"
        assertEquals(HardcoverProgressResult.Refused("nope"), update())
    }
}
