package com.tortugapower.audiobookplayer.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.database.dao.LibraryDao
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * In-memory Room integration tests for the DAO query semantics that couldn't be unit-tested from :app
 * (they're SQL, not Kotlin) — now that the DAO lives in the testable :core module. Runs under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: LibraryDao

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        dao = db.libraryDao()
    }

    @After fun tearDown() = db.close()

    private fun item(
        uuid: String, title: String, author: String?, path: String,
        type: ItemType = ItemType.BOOK, lastPlay: Long? = null
    ) = LibraryItemEntity(
        uuid = uuid, title = title, author = author, duration = 0.0, currentTime = 0.0,
        percentCompleted = 0.0, relativePath = path, remoteURL = null, artworkURL = null,
        originalFileName = null, orderRank = 0, isFinished = false, lastPlayDate = lastPlay,
        parentFolderUuid = null, type = type
    )

    @Test fun searchAllBooks_matchesTitleOrAuthor_includesBound_excludesFolder_newestFirst() = runBlocking {
        dao.insertItem(item("1", "Dune", "Frank Herbert", "Dune.m4b", lastPlay = 100))
        dao.insertItem(item("2", "Foundation", "Isaac Dune", "Foundation.m4b", lastPlay = 300)) // author matches
        dao.insertItem(item("3", "Dune Saga", "Herbert", "DuneSaga", ItemType.BOUND, lastPlay = 200)) // bound, title matches
        dao.insertItem(item("4", "Dune Folder", null, "DuneFolder", ItemType.FOLDER, lastPlay = 400)) // folder, excluded
        dao.insertItem(item("5", "Neuromancer", "Gibson", "Neuro.m4b", lastPlay = 500)) // no match

        val result = dao.searchAllBooksSync("dune", 50)

        // title OR author match, FOLDER excluded, BOUND included, ordered by lastPlayDate DESC.
        assertEquals(listOf("2", "3", "1"), result.map { it.uuid })
    }

    @Test fun searchAllBooks_respectsLimit() = runBlocking {
        repeat(5) { i -> dao.insertItem(item("b$i", "Book Dune $i", null, "b$i.m4b", lastPlay = i.toLong())) }
        assertEquals(2, dao.searchAllBooksSync("dune", 2).size)
    }

    @Test fun getRecentPlayed_onlyPlayedBooksAndBound_newestFirst() = runBlocking {
        dao.insertItem(item("1", "A", null, "A.m4b", lastPlay = 100))
        dao.insertItem(item("2", "B", null, "B", ItemType.BOUND, lastPlay = 300))
        dao.insertItem(item("3", "C never played", null, "C.m4b", lastPlay = null)) // excluded (no lastPlayDate)
        dao.insertItem(item("4", "D folder", null, "D", ItemType.FOLDER, lastPlay = 999)) // excluded (folder)

        val recent = dao.getRecentPlayedItemsSync(50)
        assertEquals(listOf("2", "1"), recent.map { it.uuid })
    }
}
