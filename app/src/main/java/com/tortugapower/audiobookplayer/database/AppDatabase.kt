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
    entities = [LibraryItemEntity::class, ChapterEntity::class, BookmarkEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao

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

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "bookplayer.db"
                )
                .addMigrations(MIGRATION_1_2)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
