package com.tortugapower.audiobookplayer.logic

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.model.SyncableItem
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers the fetch_contents upsert's artwork semantics: server artwork wins when present (the PRO
 * thumbnail flow), but a server null must NOT wipe LOCAL artwork the server never knew about — e.g.
 * a stream import's cover downloaded into Artworks/ (metadata uploads deliberately don't send the
 * device-local path), which otherwise vanished from the row on the next library fetch.
 */
@RunWith(RobolectricTestRunner::class)
class LibraryContentsSyncTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun tearDown() = db.close()

    private fun remote(uuid: String, artworkURL: String?) = SyncableItem(
        uuid = uuid, relativePath = "book.mp3", title = "Book", details = "Author",
        originalFileName = "book.mp3", duration = 100.0, currentTime = 0.0,
        percentCompleted = 0.0, isFinished = false, orderRank = 0,
        type = ItemType.BOOK.ordinal, remoteURL = null, artworkURL = artworkURL,
        speed = null, lastPlayDateTimestamp = null,
    )

    private suspend fun seedLocal(uuid: String, artworkURL: String?) {
        db.libraryDao().insertItem(
            LibraryItemEntity(
                uuid = uuid, title = "Book", relativePath = "book.mp3",
                type = ItemType.BOOK, orderRank = 0, artworkURL = artworkURL,
            )
        )
    }

    @Test fun upsert_serverNullArtwork_preservesLocalArtwork() = runBlocking {
        seedLocal("b1", artworkURL = "/data/Artworks/local-cover.jpg")

        LibraryContentsSync.upsertItem(db.libraryDao(), null, remote("b1", artworkURL = null), mutableSetOf())

        // The stream import's locally-downloaded cover survives the fetch round-trip.
        assertEquals("/data/Artworks/local-cover.jpg", db.libraryDao().getItemById("b1")?.artworkURL)
    }

    @Test fun upsert_serverArtwork_winsOverLocal() = runBlocking {
        seedLocal("b1", artworkURL = "/data/Artworks/local-cover.jpg")

        LibraryContentsSync.upsertItem(
            db.libraryDao(), null, remote("b1", artworkURL = "https://cdn/thumb.jpg"), mutableSetOf()
        )

        // The PRO thumbnail flow stays authoritative when the server actually has artwork.
        assertEquals("https://cdn/thumb.jpg", db.libraryDao().getItemById("b1")?.artworkURL)
    }

    @Test fun upsert_newItem_takesServerArtworkOrNull() = runBlocking {
        LibraryContentsSync.upsertItem(db.libraryDao(), null, remote("b2", artworkURL = null), mutableSetOf())

        assertEquals(null, db.libraryDao().getItemById("b2")?.artworkURL)
    }

    @Test fun updateParentFoldersBatch_recomputesSubFoldersBeforeSharedGrandparent() = runBlocking {
        // Library/A/bookA + Library/B/bookB: the batch must recompute A and B (depth-first) BEFORE
        // Library, so the grandparent aggregates the FRESH sub-folder totals — the depth-descending
        // sort is load-bearing because getItemsInPathSync returns direct children only.
        db.libraryDao().insertItem(LibraryItemEntity(uuid = "g", title = "Library", relativePath = "Library", type = ItemType.FOLDER, orderRank = 0))
        db.libraryDao().insertItem(LibraryItemEntity(uuid = "fa", title = "A", relativePath = "Library/A", type = ItemType.FOLDER, orderRank = 0, parentFolderUuid = "g"))
        db.libraryDao().insertItem(LibraryItemEntity(uuid = "fb", title = "B", relativePath = "Library/B", type = ItemType.FOLDER, orderRank = 0, parentFolderUuid = "g"))
        db.libraryDao().insertItem(LibraryItemEntity(uuid = "ba", title = "BookA", relativePath = "Library/A/BookA.mp3", type = ItemType.BOOK, orderRank = 0, duration = 100.0, currentTime = 50.0, parentFolderUuid = "fa"))
        db.libraryDao().insertItem(LibraryItemEntity(uuid = "bb", title = "BookB", relativePath = "Library/B/BookB.mp3", type = ItemType.BOOK, orderRank = 0, duration = 300.0, currentTime = 300.0, isFinished = true, parentFolderUuid = "fb"))

        LibraryContentsSync.updateParentFoldersBatch(
            db.libraryDao(),
            listOf("Library/A/BookA.mp3", "Library/B/BookB.mp3"),
        )

        val a = db.libraryDao().getItemById("fa")!!
        assertEquals(100.0, a.duration, 0.0)
        assertEquals("1", a.author)
        val b = db.libraryDao().getItemById("fb")!!
        assertEquals(300.0, b.duration, 0.0)
        assertEquals(true, b.isFinished)

        // Grandparent sums the recomputed sub-folders: 400 total, 350 listened, not finished (A isn't).
        val g = db.libraryDao().getItemById("g")!!
        assertEquals(400.0, g.duration, 0.0)
        assertEquals(350.0, g.currentTime, 0.0)
        assertEquals(0.875, g.percentCompleted, 0.0001)
        assertEquals(false, g.isFinished)
        assertEquals("2", g.author)
    }

    @Test fun displayDetails_localizesContainerCountsAndPassesEverythingElseThrough() {
        fun item(type: ItemType, author: String?) = LibraryItemEntity(
            uuid = "x", title = "X", relativePath = "X", type = type, orderRank = 0, author = author,
        )
        // Containers: bare local count → localized plural (the mapping every render surface uses).
        assertEquals("1 File", LibraryContentsSync.displayDetails(context, ItemType.FOLDER, "1"))
        assertEquals("3 Files", LibraryContentsSync.displayDetails(context, ItemType.FOLDER, "3"))
        assertEquals("1 Chapter", LibraryContentsSync.displayDetails(context, ItemType.BOUND, "1"))
        assertEquals("7 Chapters", LibraryContentsSync.displayDetails(context, ItemType.BOUND, "7"))
        // Books, non-numeric legacy values, and nulls pass through untouched.
        assertEquals("Jane Author", LibraryContentsSync.displayDetails(context, ItemType.BOOK, "Jane Author"))
        assertEquals("2 Files", LibraryContentsSync.displayDetails(context, ItemType.FOLDER, "2 Files"))
        assertEquals(null, LibraryContentsSync.displayDetails(context, ItemType.FOLDER, null))
        // A numeric BOOK author (e.g. an artist named "1984") must NOT be turned into a count.
        assertEquals("1984", LibraryContentsSync.displayDetails(context, ItemType.BOOK, "1984"))
    }

    @Test fun serverFolderDetails_formatsBareCountsForThePushBoundary() {
        fun item(type: ItemType, author: String?) = LibraryItemEntity(
            uuid = "x", title = "X", relativePath = "X", type = type, orderRank = 0, author = author,
        )
        // Containers: bare local count → the display format the server/iOS store ("N Files"/"N Chapters").
        assertEquals("1 File", LibraryContentsSync.serverFolderDetails(item(ItemType.FOLDER, "1")))
        assertEquals("3 Files", LibraryContentsSync.serverFolderDetails(item(ItemType.FOLDER, "3")))
        assertEquals("1 Chapter", LibraryContentsSync.serverFolderDetails(item(ItemType.BOUND, "1")))
        assertEquals("12 Chapters", LibraryContentsSync.serverFolderDetails(item(ItemType.BOUND, "12")))
        // Books and non-numeric legacy values pass through untouched.
        assertEquals("Jane Author", LibraryContentsSync.serverFolderDetails(item(ItemType.BOOK, "Jane Author")))
        assertEquals("2 Files", LibraryContentsSync.serverFolderDetails(item(ItemType.FOLDER, "2 Files")))
    }

    @Test fun updateParentFolders_multiLevelNesting_aggregatesUpHierarchy() = runBlocking {
        // Seed f1 (Books)
        db.libraryDao().insertItem(
            LibraryItemEntity(
                uuid = "f1", title = "Books", relativePath = "Books",
                type = ItemType.FOLDER, orderRank = 0, author = null
            )
        )
        // Seed f2 (Books/SciFi)
        db.libraryDao().insertItem(
            LibraryItemEntity(
                uuid = "f2", title = "SciFi", relativePath = "Books/SciFi",
                type = ItemType.FOLDER, orderRank = 0, author = null, parentFolderUuid = "f1"
            )
        )
        // Seed b1 (Books/SciFi/Book1)
        db.libraryDao().insertItem(
            LibraryItemEntity(
                uuid = "b1", title = "Book1", relativePath = "Books/SciFi/Book1",
                type = ItemType.BOOK, orderRank = 0, duration = 100.0, currentTime = 40.0,
                parentFolderUuid = "f2"
            )
        )

        LibraryContentsSync.updateParentFolders(db.libraryDao(), "Books/SciFi/Book1")

        val updatedF2 = db.libraryDao().getItemById("f2")
        assertEquals(100.0, updatedF2?.duration ?: 0.0, 0.0)
        assertEquals(40.0, updatedF2?.currentTime ?: 0.0, 0.0)
        assertEquals(0.4, updatedF2?.percentCompleted ?: 0.0, 0.0)
        assertEquals("1", updatedF2?.author)

        val updatedF1 = db.libraryDao().getItemById("f1")
        assertEquals(100.0, updatedF1?.duration ?: 0.0, 0.0)
        assertEquals(40.0, updatedF1?.currentTime ?: 0.0, 0.0)
        assertEquals(0.4, updatedF1?.percentCompleted ?: 0.0, 0.0)
        assertEquals("1", updatedF1?.author)
    }

    @Test fun updateParentFolders_deletionTriggeredRecompute_updatesCorrectly() = runBlocking {
        // Seed f1 (Books)
        db.libraryDao().insertItem(
            LibraryItemEntity(
                uuid = "f1", title = "Books", relativePath = "Books",
                type = ItemType.FOLDER, orderRank = 0
            )
        )
        // Seed b1
        db.libraryDao().insertItem(
            LibraryItemEntity(
                uuid = "b1", title = "Book1", relativePath = "Books/Book1",
                type = ItemType.BOOK, orderRank = 0, duration = 100.0, currentTime = 50.0,
                parentFolderUuid = "f1"
            )
        )
        // Seed b2
        db.libraryDao().insertItem(
            LibraryItemEntity(
                uuid = "b2", title = "Book2", relativePath = "Books/Book2",
                type = ItemType.BOOK, orderRank = 0, duration = 150.0, currentTime = 150.0,
                isFinished = true, parentFolderUuid = "f1"
            )
        )

        // Initial recompute
        LibraryContentsSync.updateParentFolders(db.libraryDao(), "Books/Book1")
        val f1Initial = db.libraryDao().getItemById("f1")
        assertEquals(250.0, f1Initial?.duration ?: 0.0, 0.0)
        assertEquals(200.0, f1Initial?.currentTime ?: 0.0, 0.0)
        assertEquals("2", f1Initial?.author)
        assertEquals(false, f1Initial?.isFinished)

        // Delete b1 from DB
        val b1Entity = db.libraryDao().getItemById("b1")!!
        db.libraryDao().deleteItem(b1Entity)

        // Trigger recompute (representing the deletion call site)
        LibraryContentsSync.updateParentFolders(db.libraryDao(), "Books/Book1")
        val f1AfterDelete = db.libraryDao().getItemById("f1")
        assertEquals(150.0, f1AfterDelete?.duration ?: 0.0, 0.0)
        assertEquals(150.0, f1AfterDelete?.currentTime ?: 0.0, 0.0)
        assertEquals("1", f1AfterDelete?.author)
        assertEquals(true, f1AfterDelete?.isFinished)
    }

    @Test fun updateParentFolders_emptyAndAllFinishedTransitions_worksCorrectly() = runBlocking {
        // Seed f1 (Books)
        db.libraryDao().insertItem(
            LibraryItemEntity(
                uuid = "f1", title = "Books", relativePath = "Books",
                type = ItemType.FOLDER, orderRank = 0
            )
        )

        // Empty transition
        LibraryContentsSync.updateParentFolders(db.libraryDao(), "Books/dummy")
        val f1Empty = db.libraryDao().getItemById("f1")
        assertEquals(0.0, f1Empty?.duration ?: 0.0, 0.0)
        assertEquals(0.0, f1Empty?.currentTime ?: 0.0, 0.0)
        assertEquals(0.0, f1Empty?.percentCompleted ?: 0.0, 0.0)
        assertEquals(false, f1Empty?.isFinished ?: true)
        assertEquals("0", f1Empty?.author)

        // Seed finished book
        db.libraryDao().insertItem(
            LibraryItemEntity(
                uuid = "b1", title = "Book1", relativePath = "Books/Book1",
                type = ItemType.BOOK, orderRank = 0, duration = 100.0, currentTime = 100.0,
                isFinished = true, parentFolderUuid = "f1"
            )
        )

        // All-finished transition
        LibraryContentsSync.updateParentFolders(db.libraryDao(), "Books/Book1")
        val f1Finished = db.libraryDao().getItemById("f1")
        assertEquals(100.0, f1Finished?.duration ?: 0.0, 0.0)
        assertEquals(100.0, f1Finished?.currentTime ?: 0.0, 0.0)
        assertEquals(1.0, f1Finished?.percentCompleted ?: 0.0, 0.0)
        assertEquals(true, f1Finished?.isFinished)
        assertEquals("1", f1Finished?.author)
    }

    // --- percentCompleted scale normalization (wire is iOS's 0..100 / legacy Android 0..1) ---

    @Test fun normalizedPercent_derivesFractionFromTimes_regardlessOfWireScale() {
        // Server value on the iOS 0..100 scale — derived fraction wins over the wire value.
        val ios = remote("b1", artworkURL = null).copy(
            duration = 200.0, currentTime = 100.0, percentCompleted = 50.0
        )
        assertEquals(0.5, LibraryContentsSync.normalizedRemotePercent(ios), 1e-9)

        // Row last written by an old Android build (raw 0..1 fraction) — same derivation, same answer.
        val legacy = ios.copy(percentCompleted = 0.5)
        assertEquals(0.5, LibraryContentsSync.normalizedRemotePercent(legacy), 1e-9)
    }

    @Test fun normalizedPercent_durationlessItems_fallBackToFinishedThenWireAs100Scale() {
        val finished = remote("b1", artworkURL = null).copy(
            duration = 0.0, currentTime = 0.0, percentCompleted = 100.0, isFinished = true
        )
        assertEquals(1.0, LibraryContentsSync.normalizedRemotePercent(finished), 1e-9)

        val unfinished = finished.copy(isFinished = false, percentCompleted = 45.0)
        assertEquals(0.45, LibraryContentsSync.normalizedRemotePercent(unfinished), 1e-9)
    }

    @Test fun upsert_finishedServerItem_storesFractionNotServerScale() = runBlocking {
        // The exact 1.1.x bug: a finished book fetched from the server stored percentCompleted=100.0
        // (the API's scale) into a column every other writer treats as 0..1 — the details screen
        // then rendered "10000%".
        val finished = remote("b9", artworkURL = null).copy(
            duration = 300.0, currentTime = 300.0, percentCompleted = 100.0, isFinished = true
        )
        LibraryContentsSync.upsertItem(db.libraryDao(), null, finished, mutableSetOf())

        val stored = db.libraryDao().getItemById("b9")!!
        assertEquals(1.0, stored.percentCompleted, 1e-9)
    }
}
