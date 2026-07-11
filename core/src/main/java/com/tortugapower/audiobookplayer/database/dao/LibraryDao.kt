package com.tortugapower.audiobookplayer.database.dao

import androidx.room.*
import com.tortugapower.audiobookplayer.database.entities.BookmarkEntity
import com.tortugapower.audiobookplayer.database.entities.ChapterEntity
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.BookCompletionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Query("SELECT * FROM library_items WHERE relativePath NOT LIKE '%/%' ORDER BY orderRank ASC")
    fun getRootItems(): Flow<List<LibraryItemEntity>>

    @Query("SELECT * FROM library_items WHERE relativePath LIKE :path || '/%' AND relativePath NOT LIKE :path || '/%/%' ORDER BY orderRank ASC")
    fun getItemsInPath(path: String): Flow<List<LibraryItemEntity>>

    @Query("SELECT * FROM library_items WHERE relativePath LIKE :path || '/%' AND relativePath NOT LIKE :path || '/%/%' ORDER BY orderRank ASC")
    suspend fun getItemsInPathSync(path: String): List<LibraryItemEntity>

    @Query("SELECT * FROM library_items WHERE relativePath NOT LIKE '%/%' ORDER BY orderRank ASC")
    suspend fun getRootItemsSync(): List<LibraryItemEntity>

    @Query("SELECT * FROM library_items WHERE uuid = :uuid")
    suspend fun getItemById(uuid: String): LibraryItemEntity?

    @Query("SELECT * FROM library_items WHERE relativePath = :path LIMIT 1")
    suspend fun getItemByPath(path: String): LibraryItemEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM library_items WHERE type = 'BOOK' AND (relativePath = :fileName OR relativePath LIKE '%/' || :fileName))")
    suspend fun existsWithFileName(fileName: String): Boolean

    @Query("SELECT * FROM library_items WHERE type = 'BOOK' AND (relativePath = :fileName OR relativePath LIKE '%/' || :fileName) LIMIT 1")
    suspend fun getItemByFileName(fileName: String): LibraryItemEntity?

    @Query("SELECT * FROM library_items WHERE type = 'FOLDER' AND relativePath NOT LIKE '%/%'")
    fun getRootFolders(): Flow<List<LibraryItemEntity>>

    @Query("SELECT * FROM library_items WHERE type = 'FOLDER' AND relativePath LIKE :path || '/%' AND relativePath NOT LIKE :path || '/%/%'")
    fun getFoldersInPath(path: String): Flow<List<LibraryItemEntity>>

    @Query("SELECT * FROM library_items WHERE type = 'BOOK' AND title LIKE '%' || :query || '%' ORDER BY title ASC")
    fun searchBooks(query: String): Flow<List<LibraryItemEntity>>

    @Query("SELECT * FROM library_items WHERE type = 'BOOK'")
    suspend fun getAllBooksSync(): List<LibraryItemEntity>

    @Query("SELECT COUNT(DISTINCT bookUuid) FROM book_completions")
    fun getCompletedBooksCount(): Flow<Int>

    @Insert
    suspend fun insertCompletion(completion: BookCompletionEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM book_completions WHERE bookUuid = :bookUuid)")
    suspend fun hasCompletion(bookUuid: String): Boolean

    // One completion row per book: re-finishing (or two concurrent progress updates racing on the
    // same finish) must not grow the table. Transactional so check-then-insert can't interleave.
    @Transaction
    suspend fun insertCompletionIfMissing(completion: BookCompletionEntity) {
        if (!hasCompletion(completion.bookUuid)) {
            insertCompletion(completion)
        }
    }

    @Query("SELECT * FROM library_items")
    suspend fun getAllItemsSync(): List<LibraryItemEntity>

    @Query("SELECT MAX(orderRank) FROM library_items WHERE relativePath NOT LIKE '%/%'")
    suspend fun getMaxRootOrderRank(): Int?

    @Query("SELECT MAX(orderRank) FROM library_items WHERE relativePath LIKE :path || '/%' AND relativePath NOT LIKE :path || '/%/%'")
    suspend fun getMaxPathOrderRank(path: String): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: LibraryItemEntity)

    @Update
    suspend fun updateItem(item: LibraryItemEntity)

    @Delete
    suspend fun deleteItem(item: LibraryItemEntity)

    @Delete
    suspend fun deleteItems(items: List<LibraryItemEntity>)

    @Query("SELECT * FROM library_items WHERE relativePath LIKE :path || '/%'")
    suspend fun getDescendantsOfPath(path: String): List<LibraryItemEntity>

    @Query("SELECT * FROM chapters WHERE bookUuid = :bookUuid ORDER BY `index` ASC")
    fun getChaptersForBook(bookUuid: String): Flow<List<ChapterEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapters(chapters: List<ChapterEntity>)

    @Query("DELETE FROM chapters WHERE bookUuid = :bookUuid")
    suspend fun deleteChaptersForBook(bookUuid: String)

    /**
     * Idempotently set a book's chapters. Delete-then-insert in one transaction so two concurrent
     * first-play back-fills of the same synced book (both passing an "is empty?" check) can't leave
     * duplicated rows — the last transaction wins with exactly one set. `ChapterEntity` has no unique
     * index on `(bookUuid, index)`, so `insertChapters`' REPLACE cannot dedupe on its own.
     */
    @Transaction
    suspend fun replaceChaptersForBook(bookUuid: String, chapters: List<ChapterEntity>) {
        deleteChaptersForBook(bookUuid)
        insertChapters(chapters)
    }

    @Query("SELECT * FROM bookmarks WHERE bookUuid = :bookUuid ORDER BY time ASC")
    fun getBookmarksForBook(bookUuid: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE bookUuid = :bookUuid AND time = :time LIMIT 1")
    suspend fun getBookmarkAtTime(bookUuid: String, time: Double): BookmarkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: BookmarkEntity): Long

    @Update
    suspend fun updateBookmark(bookmark: BookmarkEntity)

    @Delete
    suspend fun deleteBookmark(bookmark: BookmarkEntity)

    @Transaction
    suspend fun migrateItemUuid(oldUuid: String, newUuid: String) {
        val item = getItemById(oldUuid) ?: return
        
        // 1. Update Chapters to the new UUID
        updateChaptersUuid(oldUuid, newUuid)
        // 2. Update Bookmarks to the new UUID
        updateBookmarksUuid(oldUuid, newUuid)
        // 3. Delete old item
        deleteItem(item)
        // 4. Insert new item with new UUID
        insertItem(item.copy(uuid = newUuid))
    }

    @Query("UPDATE chapters SET bookUuid = :newUuid WHERE bookUuid = :oldUuid")
    suspend fun updateChaptersUuid(oldUuid: String, newUuid: String)

    @Query("UPDATE bookmarks SET bookUuid = :newUuid WHERE bookUuid = :oldUuid")
    suspend fun updateBookmarksUuid(oldUuid: String, newUuid: String)

    @Query("SELECT * FROM external_resources WHERE libraryItemUuid = :itemUuid AND providerName = :provider LIMIT 1")
    suspend fun getExternalResource(itemUuid: String, provider: String): com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity?

    @Query("SELECT * FROM external_resources WHERE providerName = :providerName AND providerId = :providerId LIMIT 1")
    suspend fun getExternalResourceByProvider(providerName: String, providerId: String): com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity?

    @Query("SELECT * FROM external_resources WHERE libraryItemUuid = :itemUuid")
    fun getExternalResourcesForBookFlow(itemUuid: String): Flow<List<com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity>>

    @Query("SELECT * FROM external_resources WHERE libraryItemUuid = :itemUuid")
    suspend fun getExternalResourcesForBookSync(itemUuid: String): List<com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExternalResource(externalResource: com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity)

    /**
     * Insert a library item together with its external resource atomically — a failure in the second
     * insert must not leave an orphaned item without its backing resource (a stream item without its
     * "stream" resource is unplayable: resolveStreamingUrl has nothing to rebuild the URL from).
     */
    @Transaction
    suspend fun insertItemWithExternalResource(
        item: LibraryItemEntity,
        externalResource: com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity,
    ) {
        insertItem(item)
        insertExternalResource(externalResource)
    }

    @Query("DELETE FROM external_resources WHERE libraryItemUuid = :itemUuid AND providerName = :provider")
    suspend fun deleteExternalResource(itemUuid: String, provider: String)

    @Transaction
    @Query("SELECT * FROM library_items WHERE relativePath NOT LIKE '%/%' ORDER BY orderRank ASC")
    fun getRootItemsWithResources(): Flow<List<com.tortugapower.audiobookplayer.database.entities.LibraryItemWithExternalResources>>

    @Transaction
    @Query("SELECT * FROM library_items WHERE relativePath LIKE :path || '/%' AND relativePath NOT LIKE :path || '/%/%' ORDER BY orderRank ASC")
    fun getItemsInPathWithResources(path: String): Flow<List<com.tortugapower.audiobookplayer.database.entities.LibraryItemWithExternalResources>>

    @Transaction
    @Query("SELECT * FROM library_items WHERE type = 'BOOK' AND title LIKE '%' || :query || '%' ORDER BY title ASC")
    fun searchBooksWithResources(query: String): Flow<List<com.tortugapower.audiobookplayer.database.entities.LibraryItemWithExternalResources>>

    @Transaction
    @Query("SELECT * FROM library_items WHERE uuid = :uuid")
    suspend fun getItemByIdWithResources(uuid: String): com.tortugapower.audiobookplayer.database.entities.LibraryItemWithExternalResources?

    @Transaction
    @Query("SELECT * FROM library_items WHERE relativePath = :path LIMIT 1")
    suspend fun getItemByPathWithResources(path: String): com.tortugapower.audiobookplayer.database.entities.LibraryItemWithExternalResources?

    @Transaction
    @Query("SELECT * FROM library_items WHERE relativePath LIKE :path || '/%' AND relativePath NOT LIKE :path || '/%/%' ORDER BY orderRank ASC")
    suspend fun getItemsInPathSyncWithResources(path: String): List<com.tortugapower.audiobookplayer.database.entities.LibraryItemWithExternalResources>

    @Query("SELECT * FROM library_items WHERE type = 'BOOK' AND isFinished = 0 ORDER BY lastPlayDate IS NULL ASC, lastPlayDate DESC, title ASC LIMIT :limit")
    suspend fun getRecentUnfinishedBooksSync(limit: Int): List<LibraryItemEntity>

    // Recently played PLAYABLE items — BOOK and BOUND (a multi-file audiobook you're actively listening
    // to must appear in Android Auto's Recent tab). Only actually-played items (lastPlayDate set), newest
    // first. Distinct from getRecentUnfinishedBooksSync (BOOK-only, used by the home-screen widget).
    @Query("SELECT * FROM library_items WHERE type IN ('BOOK', 'BOUND') AND lastPlayDate IS NOT NULL ORDER BY lastPlayDate DESC LIMIT :limit")
    suspend fun getRecentPlayedItemsSync(limit: Int): List<LibraryItemEntity>

    // Reactive variant: re-emits when the recent set changes (e.g. a contents sync writes lastPlayDate), so
    // the phone can re-publish the watch's recent list live instead of only on a playback change.
    @Query("SELECT * FROM library_items WHERE type IN ('BOOK', 'BOUND') AND lastPlayDate IS NOT NULL ORDER BY lastPlayDate DESC LIMIT :limit")
    fun getRecentPlayedItems(limit: Int): Flow<List<LibraryItemEntity>>

    // Global search over playable items (BOOK + BOUND, excludes folders) matching title OR author,
    // newest-played first — mirrors iOS LibraryService.searchAllBooks. Shared by Android Auto's in-car
    // search (suspend, capped) and the in-app library search tab (reactive Flow, with resources).
    // Distinct from searchBooks (BOOK + title only), which StorageManagementScreen uses to list all books.
    @Query("SELECT * FROM library_items WHERE type != 'FOLDER' AND (title LIKE '%' || :query || '%' OR author LIKE '%' || :query || '%') ORDER BY lastPlayDate DESC LIMIT :limit")
    suspend fun searchAllBooksSync(query: String, limit: Int): List<LibraryItemEntity>

    @Transaction
    @Query("SELECT * FROM library_items WHERE type != 'FOLDER' AND (title LIKE '%' || :query || '%' OR author LIKE '%' || :query || '%') ORDER BY lastPlayDate DESC")
    fun searchAllBooksWithResources(query: String): Flow<List<com.tortugapower.audiobookplayer.database.entities.LibraryItemWithExternalResources>>
}
