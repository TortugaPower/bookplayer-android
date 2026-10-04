package com.tortugapower.audiobookplayer.database

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins MIGRATION_12_13 against the v12 library_items table (Room's own CREATE statement for it, as in
 * Migration11To12Test): items survive unconfirmed, and the migrated table has exactly the columns Room expects
 * from the entity, which Room checks when it opens a migrated database.
 */
@RunWith(RobolectricTestRunner::class)
class Migration12To13Test {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun columns(db: SupportSQLiteDatabase): Set<List<Any?>> =
        db.query("PRAGMA table_info(library_items)").use { cursor ->
            buildSet {
                while (cursor.moveToNext()) {
                    // name, type, notnull, dflt_value, pk
                    add(listOf(cursor.getString(1), cursor.getString(2), cursor.getInt(3), cursor.getString(4), cursor.getInt(5)))
                }
            }
        }

    @Test
    fun migration_addsServerKnown_andLeavesEveryItemUnconfirmed() {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null) // in-memory
            .callback(object : SupportSQLiteOpenHelper.Callback(12) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `library_items` (`uuid` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                            "`author` TEXT, `duration` REAL NOT NULL, `currentTime` REAL NOT NULL, " +
                            "`percentCompleted` REAL NOT NULL, `relativePath` TEXT, `remoteURL` TEXT, `artworkURL` TEXT, " +
                            "`originalFileName` TEXT, `orderRank` INTEGER NOT NULL, `isFinished` INTEGER NOT NULL, " +
                            "`lastPlayDate` INTEGER, `parentFolderUuid` TEXT, `type` TEXT NOT NULL, PRIMARY KEY(`uuid`))"
                    )
                    db.execSQL(
                        "INSERT INTO library_items (uuid, title, duration, currentTime, percentCompleted, relativePath, " +
                            "orderRank, isFinished, type) VALUES ('book-uuid', 'Book', 60.0, 12.5, 0.2, 'Book.m4b', 3, 0, 'BOOK')"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()

        val migratedColumns = FrameworkSQLiteOpenHelperFactory().create(config).use { helper ->
            val db = helper.writableDatabase
            AppDatabase.MIGRATION_12_13.migrate(db)

            db.query("SELECT title, currentTime, relativePath, serverKnown FROM library_items WHERE uuid = 'book-uuid'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Book", cursor.getString(0))
                assertEquals(12.5, cursor.getDouble(1), 0.0)
                assertEquals("Book.m4b", cursor.getString(2))
                assertEquals("unconfirmed until the first sync's pass", 0, cursor.getInt(3))
            }
            columns(db)
        }

        val roomDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val expectedColumns = try {
            columns(roomDb.openHelper.writableDatabase)
        } finally {
            roomDb.close()
        }

        assertEquals(expectedColumns, migratedColumns)
    }
}
