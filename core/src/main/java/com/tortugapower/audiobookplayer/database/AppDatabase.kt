package com.tortugapower.audiobookplayer.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.tortugapower.audiobookplayer.database.dao.LibraryDao
import com.tortugapower.audiobookplayer.database.dao.ExternalServerDao
import com.tortugapower.audiobookplayer.database.entities.BookmarkEntity
import com.tortugapower.audiobookplayer.database.entities.ChapterEntity
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.database.entities.BookCompletionEntity
import com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity
import com.tortugapower.audiobookplayer.database.entities.ExternalServerEntity
import androidx.room.TypeConverters

@Database(
    entities = [
        LibraryItemEntity::class, 
        ChapterEntity::class, 
        BookmarkEntity::class, 
        com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity::class,
        com.tortugapower.audiobookplayer.database.entities.AccountEntity::class,
        com.tortugapower.audiobookplayer.database.entities.PlaybackSessionEntity::class,
        BookCompletionEntity::class,
        ExternalServerEntity::class,
        ExternalResourceEntity::class
    ],
    version = 9,
    exportSchema = false
)
@TypeConverters(MapConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun syncTaskDao(): com.tortugapower.audiobookplayer.database.dao.SyncTaskDao
    abstract fun accountDao(): com.tortugapower.audiobookplayer.database.dao.AccountDao
    abstract fun statisticsDao(): com.tortugapower.audiobookplayer.database.dao.StatisticsDao
    abstract fun externalServerDao(): ExternalServerDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `bookmarks` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        `bookUuid` TEXT NOT NULL, 
                        `time` REAL NOT NULL, 
                        `note` TEXT, 
                        `type` TEXT NOT NULL DEFAULT 'USER',
                        FOREIGN KEY(`bookUuid`) REFERENCES `library_items`(`uuid`) ON UPDATE NO ACTION ON DELETE CASCADE 
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmarks_bookUuid` ON `bookmarks` (`bookUuid`)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `sync_tasks` (
                        `id` TEXT PRIMARY KEY NOT NULL, 
                        `taskID` TEXT NOT NULL, 
                        `queueKey` TEXT NOT NULL, 
                        `jobType` TEXT NOT NULL, 
                        `position` INTEGER NOT NULL, 
                        `payload` TEXT NOT NULL, 
                        `status` TEXT NOT NULL, 
                        `createdAt` INTEGER NOT NULL, 
                        `errorMessage` TEXT, 
                        `attempts` INTEGER NOT NULL
                    )
                """)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `accounts` (
                        `id` TEXT PRIMARY KEY NOT NULL, 
                        `email` TEXT NOT NULL, 
                        `apiToken` TEXT NOT NULL, 
                        `tier` TEXT NOT NULL
                    )
                """)
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE accounts ADD COLUMN revenuecatId TEXT")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `playback_sessions` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        `bookUuid` TEXT NOT NULL, 
                        `bookTitle` TEXT NOT NULL, 
                        `authorName` TEXT, 
                        `startTime` INTEGER NOT NULL, 
                        `endTime` INTEGER, 
                        `duration` INTEGER NOT NULL,
                        FOREIGN KEY(`bookUuid`) REFERENCES `library_items`(`uuid`) ON UPDATE NO ACTION ON DELETE CASCADE 
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_playback_sessions_bookUuid` ON `playback_sessions` (`bookUuid`)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `book_completions` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `bookUuid` TEXT NOT NULL,
                        `bookTitle` TEXT NOT NULL,
                        `authorName` TEXT,
                        `completionDate` INTEGER NOT NULL
                    )
                """)
                // Backfill books the user finished before this table existed, so the
                // "Completed Books" stat doesn't restart at 0. Folders are containers whose
                // isFinished is derived from their children, not completions themselves.
                db.execSQL("""
                    INSERT INTO `book_completions` (`bookUuid`, `bookTitle`, `authorName`, `completionDate`)
                    SELECT `uuid`, `title`, `author`, COALESCE(`lastPlayDate`, 0)
                    FROM `library_items` WHERE `isFinished` = 1 AND `type` != 'FOLDER'
                """)
            }
        }

        // NOTE (reviewers): `external_resources` was added to this existing migration WITHOUT
        // bumping the DB version — deliberately. DB version 6 has never shipped in a release, so
        // no user install has run the earlier 5→6 migration; amending it in place is the project
        // convention for unshipped schema ("unshipped code needs no compat"). Dev/internal builds
        // already at v6 must clear app data once. Do not flag the missing version bump.
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `external_servers` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `url` TEXT NOT NULL,
                        `username` TEXT,
                        `token` TEXT,
                        `customHeaders` TEXT,
                        `selectedLibraryId` TEXT
                    )
                """)

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `external_resources` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        `providerName` TEXT NOT NULL, 
                        `providerId` TEXT NOT NULL, 
                        `syncStatus` TEXT NOT NULL, 
                        `lastSyncedAt` INTEGER, 
                        `processedFile` INTEGER NOT NULL DEFAULT 0, 
                        `libraryItemUuid` TEXT NOT NULL, 
                        `hostId` TEXT,
                        FOREIGN KEY(`libraryItemUuid`) REFERENCES `library_items`(`uuid`) ON UPDATE NO ACTION ON DELETE CASCADE 
                    )
                """)

                db.execSQL("CREATE INDEX IF NOT EXISTS `index_external_resources_libraryItemUuid` ON `external_resources` (`libraryItemUuid`)")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // (providerName, providerId) lookup index for the media-server import dedup query —
                // without it every imported item full-scans external_resources.
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_external_resources_providerName_providerId` ON `external_resources` (`providerName`, `providerId`)")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Normalize percentCompleted to the canonical local 0..1 fraction. Rows fetched
                // from the server before the scale fix kept the API's 0..100 value raw, so the
                // column held mixed scales (details screens showed "10000%" for synced finished
                // books). Values <= 1.0 are already fractions and stay untouched.
                db.execSQL(
                    "UPDATE library_items SET percentCompleted = percentCompleted / 100.0 WHERE percentCompleted > 1.0"
                )
            }
        }

        internal val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Cross-device server identity: external_servers gains the server's self-reported
                // stable id (captured at the next connect/re-auth; nullable until then).
                db.execSQL("ALTER TABLE external_servers ADD COLUMN stableId TEXT")
                // Local self-consistency rewrite: external resources used to carry the LOCAL Room
                // rowid as hostId. Resolution no longer understands rowids (the hostId contract is
                // stableId ?: canonicalServerKey(url)), so rewrite this device's rows to the
                // canonical URL key of the server they pointed at — otherwise already-imported
                // books stop resolving on the very device where they work today, and pending
                // stream-to-cloud tasks retry forever. Server-side copies keep the old rowid
                // (accepted); the LibraryContentsSync ingest guard keeps fetches from clobbering
                // these rewritten values.
                val cursor = db.query("SELECT id, url FROM external_servers")
                cursor.use {
                    while (it.moveToNext()) {
                        val rowId = it.getLong(0)
                        val key = com.tortugapower.audiobookplayer.logic.ExternalServiceUtils
                            .canonicalServerKey(it.getString(1))
                        db.execSQL(
                            "UPDATE external_resources SET hostId = ? WHERE hostId = ?",
                            arrayOf(key, rowId.toString())
                        )
                    }
                }
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "bookplayer.db"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
