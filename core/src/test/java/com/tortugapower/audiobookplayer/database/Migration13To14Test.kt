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
 * Pins MIGRATION_13_14 against the v13 library_items table (Room's own CREATE statement for it, as in
 * Migration12To13Test): library_items gains the nullable per-book `speed` column, existing rows keep what they
 * had with a null speed, and the migrated table has exactly the columns Room expects from the entity.
 */
@RunWith(RobolectricTestRunner::class)
class Migration13To14Test {

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
    fun migration_addsNullableSpeedColumn_keepingExistingRows() {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null) // in-memory
            .callback(object : SupportSQLiteOpenHelper.Callback(13) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `library_items` (`uuid` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                            "`author` TEXT, `duration` REAL NOT NULL, `currentTime` REAL NOT NULL, " +
                            "`percentCompleted` REAL NOT NULL, `relativePath` TEXT, `remoteURL` TEXT, `artworkURL` TEXT, " +
                            "`originalFileName` TEXT, `orderRank` INTEGER NOT NULL, `isFinished` INTEGER NOT NULL, " +
                            "`lastPlayDate` INTEGER, `parentFolderUuid` TEXT, `type` TEXT NOT NULL, " +
                            "`serverKnown` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`uuid`))"
                    )
                    db.execSQL(
                        "INSERT INTO library_items (uuid, title, duration, currentTime, percentCompleted, relativePath, " +
                            "orderRank, isFinished, type, serverKnown) VALUES ('u1', 'Book A', 60.0, 12.5, 0.2, 'Book A.m4b', 3, 0, 'BOOK', 1)"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()

        val migratedColumns = FrameworkSQLiteOpenHelperFactory().create(config).use { helper ->
            val db = helper.writableDatabase
            AppDatabase.MIGRATION_13_14.migrate(db)

            db.query("SELECT uuid, title, currentTime, serverKnown, speed FROM library_items").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("u1", cursor.getString(0))
                assertEquals("Book A", cursor.getString(1))
                assertEquals(12.5, cursor.getDouble(2), 0.0)
                assertEquals(1, cursor.getInt(3))
                assertTrue(cursor.isNull(4))
                assertEquals(1, cursor.count)
            }
            db.execSQL("UPDATE library_items SET speed = 1.5 WHERE uuid = 'u1'")
            db.query("SELECT speed FROM library_items WHERE uuid = 'u1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1.5, cursor.getDouble(0), 0.0)
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
