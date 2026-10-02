package com.tortugapower.audiobookplayer.logic

import android.content.Context
import android.util.Log
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.dao.LibraryDao
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.database.entities.BookmarkType
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * An upload the server answered `item_not_found` for: it lost the book, so the book is registered again
 * through the sync lane, like a fresh upload, rather than parking a task the user can't fix (iOS
 * `handBackUpload`, bookplayer-api `docs/multipart-uploads.md`). Once per book per process (an explicit
 * Retry allows another); a second `item_not_found` parks as usual. As on iOS, a book deleted on another
 * device comes back this way: the server can't tell the two apart from an upload.
 */
object UploadHandBack {
    private val handedBack = ConcurrentHashMap.newKeySet<String>()

    /** True the first time for [uuid] in this process */
    fun claim(uuid: String): Boolean = handedBack.add(uuid)

    /** An explicit Retry asks for the book again: it gets another registration */
    fun release(uuid: String) {
        handedBack.remove(uuid)
    }

    /**
     * Queues the book's registration, its external resources and bookmarks; the registration's answer
     * queues a new upload (a media-server book's queues it from the sync lane, after the registration,
     * since its registration never asks for its file). False when the book is gone on this device too.
     */
    suspend fun reRegister(libraryDao: LibraryDao, repository: SyncTaskRepository, uuid: String): Boolean {
        val item = libraryDao.getItemById(uuid) ?: return false
        SyncTaskFactory.createUploadMetadataTask(repository, item)
        val resources = libraryDao.getExternalResourcesForBookSync(uuid)
        resources.forEach { SyncTaskFactory.createUploadExternalResourceTask(repository, it) }
        item.relativePath?.let { path ->
            libraryDao.getBookmarksForBook(uuid).first()
                .filter { it.type == BookmarkType.USER }
                .forEach { SyncTaskFactory.createSetBookmarkTask(repository, it, item.title, path) }
        }
        if (resources.any { ExternalServiceUtils.serviceTypeFor(it.providerName) != null }) {
            SyncTaskFactory.createQueueFileUploadTask(repository, item)
        }
        return true
    }
}

/**
 * Whether a queued task is part of getting a book's file to S3 (iOS `pendingBookUploads`): the upload
 * itself, the sync-lane step that queues it, or a book's registration, whose answer queues it (also the
 * gap after `item_not_found`, while the book is registered again). Removing the book's file before then
 * loses the only copy. A media-server book's registration never asks for its file (it goes up once it's
 * downloaded, and the media server keeps it meanwhile), so it doesn't count.
 */
fun leadsToBookUpload(task: SyncTaskEntity, isMediaServerBook: Boolean): Boolean = when (task.jobType) {
    SyncTaskFactory.JOB_UPLOAD_FILE, SyncTaskFactory.JOB_QUEUE_FILE_UPLOAD -> true
    SyncTaskFactory.JOB_UPLOAD_METADATA -> !isMediaServerBook && registeredType(task.payload) == ItemType.BOOK.ordinal
    else -> false
}

private fun registeredType(payload: String): Int? = runCatching {
    com.google.gson.JsonParser.parseString(payload).asJsonObject.get("type")?.asInt
}.getOrNull()

/** Whether the book's file upload should be queued: a PRO account's book with its file on this device */
internal fun shouldUploadFile(tier: AccountTier?, item: LibraryItemEntity, file: File?): Boolean =
    tier == AccountTier.PRO && item.type == ItemType.BOOK && file?.isFile == true

/**
 * Runs [SyncTaskFactory.createQueueFileUploadTask]'s task: queues the book's file upload when the
 * account is PRO and the file is on this device. No server call.
 */
class QueueFileUploadProcessor(
    private val repository: SyncTaskRepository,
    private val libraryDao: () -> LibraryDao,
    private val accountTier: suspend () -> AccountTier?,
    private val bookFile: (relativePath: String) -> File,
) : TaskProcessor {
    override fun canHandle(jobType: String): Boolean = jobType == SyncTaskFactory.JOB_QUEUE_FILE_UPLOAD

    override suspend fun process(task: SyncTaskEntity): Boolean {
        val uuid = UploadFilePayload.uuid(task.payload) ?: return true
        val item = libraryDao().getItemById(uuid) ?: return true
        val file = item.relativePath?.let(bookFile)
        if (shouldUploadFile(accountTier(), item, file)) {
            SyncTaskFactory.createUploadFileTask(repository, item)
        } else {
            Log.d("QueueFileUpload", "Not queuing ${task.id}: not PRO, not a book, or no file here")
        }
        return true
    }

    companion object {
        fun create(context: Context, repository: SyncTaskRepository): QueueFileUploadProcessor {
            val appContext = context.applicationContext
            return QueueFileUploadProcessor(
                repository = repository,
                libraryDao = { AppDatabase.getDatabase(appContext).libraryDao() },
                accountTier = { AppDatabase.getDatabase(appContext).accountDao().getAccount()?.tier },
                bookFile = { OfflineDownloadManager.processedFile(appContext, it) },
            )
        }
    }
}
