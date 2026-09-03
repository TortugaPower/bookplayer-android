package com.tortugapower.audiobookplayer.database

import android.content.Context
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
 * Pins MIGRATION_9_10 against a minimal v9-shaped fixture (exportSchema=false rules out
 * MigrationTestHelper — same approach as Migration8To9Test): external_servers gains the nullable
 * userId column, and existing rows keep everything they had with a null userId.
 */
@RunWith(RobolectricTestRunner::class)
class Migration9To10Test {

    @Test
    fun migration_addsNullableUserIdColumn_keepingExistingRows() {
        val config = SupportSQLiteOpenHelper.Configuration.builder(
            ApplicationProvider.getApplicationContext<Context>()
        )
            .name(null) // in-memory
            .callback(object : SupportSQLiteOpenHelper.Callback(9) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE external_servers (id INTEGER NOT NULL PRIMARY KEY, url TEXT NOT NULL, username TEXT, stableId TEXT)"
                    )
                    db.execSQL("INSERT INTO external_servers VALUES (1, 'https://abs.example.com', 'gianni', 'guid-1')")
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()

        FrameworkSQLiteOpenHelperFactory().create(config).use { helper ->
            val db = helper.writableDatabase
            AppDatabase.MIGRATION_9_10.migrate(db)

            // Existing row survives with a null userId; the column is writable.
            db.query("SELECT id, username, stableId, userId FROM external_servers").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1L, cursor.getLong(0))
                assertEquals("gianni", cursor.getString(1))
                assertEquals("guid-1", cursor.getString(2))
                assertTrue(cursor.isNull(3))
                assertEquals(1, cursor.count)
            }
            db.execSQL("UPDATE external_servers SET userId = 'u1' WHERE id = 1")
            db.query("SELECT userId FROM external_servers WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("u1", cursor.getString(0))
            }
        }
    }
}
