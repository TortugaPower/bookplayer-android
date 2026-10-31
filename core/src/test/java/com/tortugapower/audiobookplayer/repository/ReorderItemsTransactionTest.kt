package com.tortugapower.audiobookplayer.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.database.AppDatabase
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

/**
 * A reorder writes its ranks in one transaction. One write per item let the library re-read between writes and
 * show two items sharing a rank in their old order, so a drop flashed. And it writes only the ranks, so it can't undo
 * a progress write that landed since the items were read.
 */
@RunWith(RobolectricTestRunner::class)
class ReorderItemsTransactionTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase

    /** Counts the DAO's item writes, passing each through. */
    private class CountingDao(private val dao: LibraryDao) : LibraryDao by dao {
        var singleWrites = 0
        var batchWrites = 0
        override suspend fun updateItem(item: LibraryItemEntity) {
            singleWrites++
            dao.updateItem(item)
        }
        override suspend fun setOrderRanks(uuids: List<String>) {
            batchWrites++
            dao.setOrderRanks(uuids)
        }
    }

    private fun seed(vararg uuids: String) = runBlocking {
        uuids.forEachIndexed { rank, uuid ->
            db.libraryDao().insertItem(
                LibraryItemEntity(uuid = uuid, title = uuid, relativePath = uuid, type = ItemType.BOOK, orderRank = rank)
            )
        }
    }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    @Test fun `a reorder writes every rank in one transaction`() = runBlocking {
        seed("a", "b", "c")
        val dao = CountingDao(db.libraryDao())
        val repository = RoomLibraryRepository(context, dao)

        repository.reorderItems(listOf("c", "a", "b").map { db.libraryDao().getItemById(it)!! })

        assertEquals(1, dao.batchWrites)
        assertEquals(0, dao.singleWrites)
        assertEquals(listOf("c", "a", "b"), db.libraryDao().getRootItemsSync().map { it.uuid })
    }

    @Test fun `a reorder writes only the ranks, keeping progress saved since the items were read`() = runBlocking {
        seed("a", "b")
        val read = listOf("b", "a").map { db.libraryDao().getItemById(it)!! }
        // Played on after the read: a whole-item write would put back the progress read above.
        db.libraryDao().updateItem(db.libraryDao().getItemById("a")!!.copy(currentTime = 120.0))

        RoomLibraryRepository(context, db.libraryDao()).reorderItems(read)

        assertEquals(120.0, db.libraryDao().getItemById("a")!!.currentTime, 0.0)
        assertEquals(listOf("b", "a"), db.libraryDao().getRootItemsSync().map { it.uuid })
    }
}
