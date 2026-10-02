package com.tortugapower.audiobookplayer.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.database.dao.LibraryDao
import com.tortugapower.audiobookplayer.database.entities.BookCompletionEntity
import com.tortugapower.audiobookplayer.database.entities.BookmarkEntity
import com.tortugapower.audiobookplayer.database.entities.ChapterEntity
import com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.database.entities.PlaybackSessionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Adopting the server's uuid for a local item (a match_uuids or listing conflict) on the real schema,
 * where foreign keys are enforced, under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ItemUuidMigrationTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: LibraryDao

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        dao = db.libraryDao()
    }

    @After fun tearDown() = db.close()

    private fun item(uuid: String, path: String, type: ItemType = ItemType.BOOK, parent: String? = null) =
        LibraryItemEntity(uuid = uuid, title = path, relativePath = path, parentFolderUuid = parent, type = type)

    private fun count(table: String, column: String, uuid: String): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table WHERE $column = ?", arrayOf(uuid)).use {
            it.moveToFirst()
            it.getInt(0)
        }

    @Test fun migrateItemUuid_movesTheBookAndEverythingThatPointsAtIt() = runBlocking {
        dao.insertItem(item("old", "Book.m4b").apply { currentTime = 42.0 })
        dao.insertChapters(listOf(ChapterEntity(bookUuid = "old", title = "One", start = 0.0, duration = 10.0, index = 0)))
        dao.insertBookmark(BookmarkEntity(bookUuid = "old", time = 5.0, note = "note"))
        dao.insertExternalResource(
            ExternalResourceEntity(providerName = "jellyfin", providerId = "jf-1", syncStatus = "stream", libraryItemUuid = "old")
        )
        db.statisticsDao().insertSession(PlaybackSessionEntity(bookUuid = "old", bookTitle = "Book", authorName = null, startTime = 1L))
        dao.insertCompletion(BookCompletionEntity(bookUuid = "old", bookTitle = "Book", authorName = null, completionDate = 2L))

        assertTrue(dao.migrateItemUuid("old", "new"))

        assertNull(dao.getItemById("old"))
        assertEquals(42.0, dao.getItemById("new")!!.currentTime, 0.0)
        assertEquals(1, dao.getChaptersForBook("new").first().size)
        assertEquals("note", dao.getBookmarksForBook("new").first().single().note)
        assertEquals("jf-1", dao.getExternalResourcesForBookSync("new").single().providerId)
        assertEquals(1, count("playback_sessions", "bookUuid", "new"))
        assertEquals(1, count("book_completions", "bookUuid", "new"))
        listOf("chapters", "bookmarks", "playback_sessions", "book_completions").forEach {
            assertEquals(it, 0, count(it, "bookUuid", "old"))
        }
        assertEquals(0, count("external_resources", "libraryItemUuid", "old"))
    }

    @Test fun migrateItemUuid_onAFolder_keepsItsChildrenInsideIt() = runBlocking {
        dao.insertItem(item("folder-old", "Folder", ItemType.FOLDER))
        dao.insertItem(item("child", "Folder/Book.m4b", parent = "folder-old"))

        assertTrue(dao.migrateItemUuid("folder-old", "folder-new"))

        assertNotNull(dao.getItemById("folder-new"))
        assertEquals("folder-new", dao.getItemById("child")!!.parentFolderUuid)
    }

    @Test fun migrateItemUuid_whenAnotherItemHasTheServersUuid_changesNothing() = runBlocking {
        dao.insertItem(item("old", "Book.m4b"))
        dao.insertItem(item("new", "Other.m4b"))
        dao.insertBookmark(BookmarkEntity(bookUuid = "new", time = 7.0))

        assertFalse(dao.migrateItemUuid("old", "new"))

        assertEquals("Book.m4b", dao.getItemById("old")!!.relativePath)
        assertEquals("Other.m4b", dao.getItemById("new")!!.relativePath)
        assertEquals(1, count("bookmarks", "bookUuid", "new"))
    }

    @Test fun migrateItemUuid_withNoLocalItem_isANoOp() = runBlocking {
        assertTrue(dao.migrateItemUuid("missing", "new"))
        assertNull(dao.getItemById("new"))
    }

    /** A retry after the item moved but before its tasks did (a crash in between) must report success */
    @Test fun migrateItemUuid_afterTheItemAlreadyMoved_returnsTrue() = runBlocking {
        dao.insertItem(item("new", "Book.m4b"))

        assertTrue(dao.migrateItemUuid("old", "new"))
        assertEquals("Book.m4b", dao.getItemById("new")!!.relativePath)
    }
}
