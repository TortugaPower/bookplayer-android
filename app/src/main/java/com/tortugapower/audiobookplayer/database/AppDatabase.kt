package com.tortugapower.audiobookplayer.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.tortugapower.audiobookplayer.database.dao.LibraryDao
import com.tortugapower.audiobookplayer.database.entities.BookmarkEntity
import com.tortugapower.audiobookplayer.database.entities.ChapterEntity
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity

@Database(
    entities = [
        LibraryItemEntity::class, 
        ChapterEntity::class, 
        BookmarkEntity::class, 
        com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity::class,
        com.tortugapower.audiobookplayer.database.entities.AccountEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun syncTaskDao(): com.tortugapower.audiobookplayer.database.dao.SyncTaskDao
    abstract fun accountDao(): com.tortugapower.audiobookplayer.database.dao.AccountDao

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

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "bookplayer.db"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
