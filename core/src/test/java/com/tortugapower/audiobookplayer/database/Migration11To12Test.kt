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
 * Pins MIGRATION_11_12 against the v11 sync_tasks table, Room's own CREATE statement for it
 * (exportSchema=false rules out MigrationTestHelper, as in the earlier migration tests): queued tasks
 * survive unparked, and the migrated table has exactly the columns Room expects from the entity, which
 * Room checks when it opens a migrated database.
 */
@RunWith(RobolectricTestRunner::class)
class Migration11To12Test {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun columns(db: SupportSQLiteDatabase): Set<List<Any?>> =
        db.query("PRAGMA table_info(sync_tasks)").use { cursor ->
            buildSet {
                while (cursor.moveToNext()) {
                    // name, type, notnull, dflt_value, pk
                    add(listOf(cursor.getString(1), cursor.getString(2), cursor.getInt(3), cursor.getString(4), cursor.getInt(5)))
                }
            }
        }

    @Test
    fun migration_addsThePauseColumns_andKeepsQueuedTasksUnparked() {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null) // in-memory
            .callback(object : SupportSQLiteOpenHelper.Callback(11) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `sync_tasks` (`id` TEXT NOT NULL, `taskID` TEXT NOT NULL, " +
                            "`queueKey` TEXT NOT NULL, `jobType` TEXT NOT NULL, `position` INTEGER NOT NULL, " +
                            "`payload` TEXT NOT NULL, `status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                            "`errorMessage` TEXT, `attempts` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                    )
                    db.execSQL(
                        "INSERT INTO sync_tasks VALUES ('t1', 'book-uuid', 'sync', 'move', 3, '{}', 'PENDING', 1000, " +
                            "'Processor returned failure', 7)"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()

        val migratedColumns = FrameworkSQLiteOpenHelperFactory().create(config).use { helper ->
            val db = helper.writableDatabase
            AppDatabase.MIGRATION_11_12.migrate(db)

            db.query(
                "SELECT taskID, position, errorMessage, attempts, pauseScope, errorCode, httpStatus, pausedAt, sentryEventId " +
                    "FROM sync_tasks WHERE id = 't1'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("book-uuid", cursor.getString(0))
                assertEquals(3, cursor.getInt(1))
                assertEquals("Processor returned failure", cursor.getString(2))
                assertEquals(7, cursor.getInt(3))
                (4..8).forEach { assertTrue("column $it is null", cursor.isNull(it)) }
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
