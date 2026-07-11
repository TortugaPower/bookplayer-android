package com.tortugapower.audiobookplayer.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the iOS-parity rules for folder↔volume conversion (LibraryService.updateFolder): a bound
 * volume may only contain books and can't be empty (violations throw BEFORE any mutation), a
 * successful →bound clears the children's lastPlayDate, and →folder clears the ex-volume's own.
 */
@RunWith(RobolectricTestRunner::class)
class BoundConversionTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var repository: RoomLibraryRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomLibraryRepository(context, db.libraryDao())
    }

    @After fun tearDown() {
        db.close()
    }

    private suspend fun seed(
        uuid: String,
        path: String,
        type: ItemType,
        lastPlayDate: Long? = null,
    ): LibraryItemEntity {
        val item = LibraryItemEntity(
            uuid = uuid,
            title = path.substringAfterLast('/'),
            relativePath = path,
            type = type,
            orderRank = 0,
            lastPlayDate = lastPlayDate,
        )
        db.libraryDao().insertItem(item)
        return item
    }

    @Test fun `folder with a nested container is rejected and left untouched`() = runBlocking {
        val folder = seed("f", "Series", ItemType.FOLDER)
        seed("b", "Series/Book.mp3", ItemType.BOOK)
        seed("sub", "Series/Volume", ItemType.BOUND)

        try {
            repository.convertFoldersToVolumes(context, listOf(folder))
            fail("expected BoundConversionException")
        } catch (e: BoundConversionException) {
            assertEquals(BoundConversionException.Reason.NOT_ONLY_BOOKS, e.reason)
        }
        // Validated BEFORE mutating: the folder row keeps its type.
        assertEquals(ItemType.FOLDER, db.libraryDao().getItemById("f")!!.type)
    }

    @Test fun `empty folder is rejected`() = runBlocking {
        val folder = seed("f", "Empty", ItemType.FOLDER)

        try {
            repository.convertFoldersToVolumes(context, listOf(folder))
            fail("expected BoundConversionException")
        } catch (e: BoundConversionException) {
            assertEquals(BoundConversionException.Reason.EMPTY_FOLDER, e.reason)
        }
        assertEquals(ItemType.FOLDER, db.libraryDao().getItemById("f")!!.type)
    }

    @Test fun `books-only folder converts and clears the children's lastPlayDate`() = runBlocking {
        val folder = seed("f", "Series", ItemType.FOLDER)
        seed("b1", "Series/One.mp3", ItemType.BOOK, lastPlayDate = 1_700_000_000_000L)
        seed("b2", "Series/Two.mp3", ItemType.BOOK, lastPlayDate = 1_700_000_100_000L)

        repository.convertFoldersToVolumes(context, listOf(folder))

        assertEquals(ItemType.BOUND, db.libraryDao().getItemById("f")!!.type)
        // The volume tracks recency as one unit (iOS nils each child's lastPlayDate).
        assertNull(db.libraryDao().getItemById("b1")!!.lastPlayDate)
        assertNull(db.libraryDao().getItemById("b2")!!.lastPlayDate)
    }

    @Test fun `volume converts back to folder and clears its own lastPlayDate`() = runBlocking {
        val volume = seed("v", "Series", ItemType.BOUND, lastPlayDate = 1_700_000_000_000L)
        seed("b", "Series/One.mp3", ItemType.BOOK)

        repository.convertVolumesToFolders(listOf(volume))

        val row = db.libraryDao().getItemById("v")!!
        assertEquals(ItemType.FOLDER, row.type)
        assertNull(row.lastPlayDate)
    }
}
