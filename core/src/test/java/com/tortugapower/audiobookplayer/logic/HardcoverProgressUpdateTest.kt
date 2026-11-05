package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity
import com.tortugapower.audiobookplayer.network.HardcoverReadProgress
import com.tortugapower.audiobookplayer.network.ReadInProgressData
import com.tortugapower.audiobookplayer.network.ReadInProgressData.Edition
import com.tortugapower.audiobookplayer.network.ReadInProgressData.Read
import com.tortugapower.audiobookplayer.network.ReadInProgressData.UserBook
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The checks a progress update makes before it writes on Hardcover's read. */
class HardcoverProgressUpdateTest {

    private val audiobook = Edition(id = 1, pages = null, audioSeconds = 36_000, readingFormatId = 2)

    private var lacksScope = false
    private var link: ExternalResourceEntity? =
        ExternalResourceEntity(providerName = "hardcover", providerId = "77", syncStatus = "reading", libraryItemUuid = "u1")
    private var userId: String? = "42"
    private var userBooks: List<UserBook>? = listOf(userBook())
    private var refusal: String? = null
    private val asked = mutableListOf<Pair<Int, Int>>()
    private val written = mutableListOf<Pair<Int, HardcoverReadProgress>>()

    private fun userBook(statusId: Int = 2, reads: List<Read> = listOf(Read(9, null, 3_600, audiobook))) =
        UserBook(id = 5, statusId = statusId, edition = null, book = null, userBookReads = reads)

    private fun update(percent: Double = 25.0) = runBlocking {
        HardcoverProgressUpdate(
            lacksReadLibrary = { lacksScope },
            hardcoverLink = { link },
            userId = { userId },
            readInProgress = { user, book -> asked += user to book; ReadInProgressData(userBooks) },
            updateReadProgress = { read, progress -> written += read to progress; refusal },
        ).update("u1", percent)
    }

    @Test fun `a Currently Reading book's progress is written on its read`() {
        assertEquals(HardcoverProgressResult.Sent, update(25.0))
        assertEquals(listOf(42 to 77), asked)
        assertEquals(listOf(9 to HardcoverReadProgress(HardcoverReadProgress.ProgressUnit.SECONDS, 9_000, null)), written)
    }

    @Test fun `a sign-in without read library sends nothing`() {
        lacksScope = true
        assertEquals(HardcoverProgressResult.MissingScope, update())
        assertTrue(asked.isEmpty() && written.isEmpty())
    }

    @Test fun `a book not linked, or not Currently Reading in the library, sends nothing`() {
        link = link!!.copy(syncStatus = "want_to_read")
        assertEquals(HardcoverProgressResult.NotReading, update())
        link = null
        assertEquals(HardcoverProgressResult.NotReading, update())
        assertTrue(asked.isEmpty())
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
