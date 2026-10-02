package com.tortugapower.audiobookplayer.ui.screens.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.logic.OfflineDownloadManager
import com.tortugapower.audiobookplayer.logic.removeLocalFile
import com.tortugapower.audiobookplayer.repository.RoomLibraryRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins Storage Management's OFFLOAD contract: removing a local file deletes the FILE but the
 * library row survives in EVERY case — a cloud book must reappear as a not-downloaded item, not
 * vanish from the library until the next fetch re-inserts it (and a plain local row must not be
 * silently destroyed either). Nothing here may touch the server (plain repository, no tasks).
 */
@RunWith(RobolectricTestRunner::class)
class StorageOffloadTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repository by lazy { RoomLibraryRepository(context, AppDatabase.getDatabase(context).libraryDao()) }

    @Before fun cleanTables() {
        runBlocking(kotlinx.coroutines.Dispatchers.IO) { AppDatabase.getDatabase(context).clearAllTables() }
    }

    private suspend fun seedBookWithFile(uuid: String, relativePath: String, remoteURL: String?): LibraryItemEntity {
        val item = LibraryItemEntity(
            uuid = uuid, title = "Book", relativePath = relativePath,
            remoteURL = remoteURL, type = ItemType.BOOK, orderRank = 0,
        )
        AppDatabase.getDatabase(context).libraryDao().insertItem(item)
        OfflineDownloadManager.processedFile(context, relativePath).apply {
            parentFile?.mkdirs()
            writeText("audio-bytes")
        }
        return item
    }

    @Test fun `cloud book offload deletes the file, keeps the row and its path identity`() = runBlocking {
        val item = seedBookWithFile("b1", "Cloud Book.m4b", remoteURL = "https://s3/presigned")

        removeLocalFile(context, repository, item)

        assertFalse(OfflineDownloadManager.processedFile(context, "Cloud Book.m4b").exists())
        val row = AppDatabase.getDatabase(context).libraryDao().getItemById("b1")
        assertNotNull("offload must never delete the library row", row)
        // relativePath is the item's identity on the server and in play/download — must survive.
        assertEquals("Cloud Book.m4b", row!!.relativePath)
    }

    @Test fun `local-only book offload also keeps the row`() = runBlocking {
        val item = seedBookWithFile("b2", "Local Book.mp3", remoteURL = null)

        removeLocalFile(context, repository, item)

        assertFalse(OfflineDownloadManager.processedFile(context, "Local Book.mp3").exists())
        assertNotNull(AppDatabase.getDatabase(context).libraryDao().getItemById("b2"))
    }

    /**
     * A book downloaded from the media-server browser keeps a "synced" link but is a plain local book
     * (it uploads like one): it keeps its path, so it can be downloaded again from the cloud. A Hardcover
     * link doesn't make a book streamed either.
     */
    @Test fun `a browser download or a Hardcover-linked book keeps its path on offload`() = runBlocking {
        val dao = AppDatabase.getDatabase(context).libraryDao()
        val imported = seedBookWithFile("b4", "Imported.m4b", remoteURL = null)
        dao.insertExternalResource(ExternalResourceEntity(providerName = "jellyfin", providerId = "jf-4", syncStatus = ExternalResourceEntity.STATUS_SYNCED, libraryItemUuid = "b4"))
        val tracked = seedBookWithFile("b5", "Tracked.m4b", remoteURL = null)
        dao.insertExternalResource(ExternalResourceEntity(providerName = "hardcover", providerId = "hc-5", syncStatus = "linked", libraryItemUuid = "b5"))

        removeLocalFile(context, repository, imported)
        removeLocalFile(context, repository, tracked)

        assertEquals("Imported.m4b", dao.getItemById("b4")!!.relativePath)
        assertEquals("Tracked.m4b", dao.getItemById("b5")!!.relativePath)
        assertEquals(ExternalResourceEntity.STATUS_SYNCED, dao.getExternalResourcesForBookSync("b4").single().syncStatus)
    }

    /** A streamed volume's path is how its books find their media-server link: offloading keeps it */
    @Test fun `a streamed volume keeps its path on offload, so its books still stream`() = runBlocking {
        val dao = AppDatabase.getDatabase(context).libraryDao()
        val volume = LibraryItemEntity(uuid = "v1", title = "Volume", relativePath = "Volume", type = ItemType.BOUND, orderRank = 0)
        dao.insertItem(volume)
        dao.insertExternalResource(ExternalResourceEntity(providerName = "audiobookshelf", providerId = "abs-v1", syncStatus = ExternalResourceEntity.STATUS_STREAM, libraryItemUuid = "v1"))
        seedBookWithFile("v1c1", "Volume/Disc 1 - 01.mp3", remoteURL = null)

        removeLocalFile(context, repository, volume)

        assertEquals("Volume", dao.getItemById("v1")!!.relativePath)
        assertNotNull(com.tortugapower.audiobookplayer.logic.MediaServerStreams.ownerOf("v1c1", dao))
    }

    @Test fun `external book offload clears the path and reverts the resource to stream`() = runBlocking {
        val item = seedBookWithFile("b3", "Jellyfin Book.m4b", remoteURL = null)
        AppDatabase.getDatabase(context).libraryDao().insertExternalResource(
            ExternalResourceEntity(
                providerName = "jellyfin", providerId = "jf-1",
                syncStatus = ExternalResourceEntity.STATUS_DOWNLOADED, libraryItemUuid = "b3", hostId = "1",
            )
        )

        removeLocalFile(context, repository, item)

        val row = AppDatabase.getDatabase(context).libraryDao().getItemById("b3")
        assertNotNull(row)
        // External items rebuild their URL from hostId+providerId, so the dead path is cleared…
        assertNull(row!!.relativePath)
        // …and the resource reverts to stream-only.
        val resource = AppDatabase.getDatabase(context).libraryDao().getExternalResourcesForBookSync("b3").single()
        assertEquals(ExternalResourceEntity.STATUS_STREAM, resource.syncStatus)
    }

    @Test fun `queued-upload guard covers a container's descendant books`() = runBlocking {
        // Upload tasks are keyed by the BOOK's uuid; offloading a folder deletes its whole
        // directory, so a queued child upload must trip the warning for the CONTAINER too.
        val dao = AppDatabase.getDatabase(context).libraryDao()
        val folder = LibraryItemEntity(uuid = "f1", title = "Series", relativePath = "Series", type = ItemType.FOLDER, orderRank = 0)
        dao.insertItem(folder)
        seedBookWithFile("child1", "Series/Part 1.mp3", remoteURL = null)
        val syncTaskRepository = com.tortugapower.audiobookplayer.repository.RoomSyncTaskRepository(
            AppDatabase.getDatabase(context).syncTaskDao()
        )
        com.tortugapower.audiobookplayer.logic.SyncTaskFactory.createUploadFileTask(
            syncTaskRepository, dao.getItemById("child1")!!
        )

        assertEquals(true, com.tortugapower.audiobookplayer.logic.hasQueuedUploadTask(syncTaskRepository, repository, folder))
        // A sibling container with no queued descendants stays clean.
        val other = LibraryItemEntity(uuid = "f2", title = "Other", relativePath = "Other", type = ItemType.FOLDER, orderRank = 1)
        dao.insertItem(other)
        assertEquals(false, com.tortugapower.audiobookplayer.logic.hasQueuedUploadTask(syncTaskRepository, repository, other))
    }

    /**
     * iOS `pendingBookUploads`: a book's registration (its answer queues the upload, also the gap after
     * item_not_found) and the step that queues a media-server book's upload count too; a media-server
     * book's registration doesn't, since it never asks for the file
     */
    @Test fun `every step on the way to the cloud trips the warning`() = runBlocking {
        val dao = AppDatabase.getDatabase(context).libraryDao()
        val syncTaskRepository = com.tortugapower.audiobookplayer.repository.RoomSyncTaskRepository(AppDatabase.getDatabase(context).syncTaskDao())
        val book = seedBookWithFile("reg1", "Registered.mp3", remoteURL = null)
        com.tortugapower.audiobookplayer.logic.SyncTaskFactory.createUploadMetadataTask(syncTaskRepository, book)
        assertEquals(true, com.tortugapower.audiobookplayer.logic.hasQueuedUploadTask(syncTaskRepository, repository, book))

        val streamed = seedBookWithFile("ms1", "Streamed.mp3", remoteURL = null)
        dao.insertExternalResource(ExternalResourceEntity(providerName = "jellyfin", providerId = "j1", syncStatus = "downloaded", libraryItemUuid = "ms1"))
        com.tortugapower.audiobookplayer.logic.SyncTaskFactory.createUploadMetadataTask(syncTaskRepository, streamed)
        assertEquals(false, com.tortugapower.audiobookplayer.logic.hasQueuedUploadTask(syncTaskRepository, repository, streamed))

        com.tortugapower.audiobookplayer.logic.SyncTaskFactory.createQueueFileUploadTask(syncTaskRepository, streamed)
        assertEquals(true, com.tortugapower.audiobookplayer.logic.hasQueuedUploadTask(syncTaskRepository, repository, streamed))
    }

    @Test fun `a browser-downloaded book's registration trips the warning`() = runBlocking {
        val dao = AppDatabase.getDatabase(context).libraryDao()
        val syncTaskRepository = com.tortugapower.audiobookplayer.repository.RoomSyncTaskRepository(AppDatabase.getDatabase(context).syncTaskDao())
        val book = seedBookWithFile("imp1", "Imported.mp3", remoteURL = null)
        dao.insertExternalResource(ExternalResourceEntity(providerName = "jellyfin", providerId = "j1", syncStatus = ExternalResourceEntity.STATUS_SYNCED, libraryItemUuid = "imp1"))
        com.tortugapower.audiobookplayer.logic.SyncTaskFactory.createUploadMetadataTask(syncTaskRepository, book)

        assertEquals(true, com.tortugapower.audiobookplayer.logic.hasQueuedUploadTask(syncTaskRepository, repository, book))
    }
}
