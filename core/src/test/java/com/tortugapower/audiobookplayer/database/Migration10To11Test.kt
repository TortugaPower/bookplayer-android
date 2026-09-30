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
 * Pins MIGRATION_10_11 against a minimal v10-shaped fixture (exportSchema=false rules out
 * MigrationTestHelper — same approach as Migration9To10Test): library_items gains the nullable
 * per-book `speed` column, and existing rows keep what they had with a null speed.
 */
@RunWith(RobolectricTestRunner::class)
class Migration10To11Test {

    @Test
    fun migration_addsNullableSpeedColumn_keepingExistingRows() {
        val config = SupportSQLiteOpenHelper.Configuration.builder(
            ApplicationProvider.getApplicationContext<Context>()
        )
            .name(null) // in-memory
            .callback(object : SupportSQLiteOpenHelper.Callback(10) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE library_items (uuid TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, currentTime REAL NOT NULL)")
                    db.execSQL("INSERT INTO library_items VALUES ('u1', 'Book A', 12.5)")
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()

        FrameworkSQLiteOpenHelperFactory().create(config).use { helper ->
            val db = helper.writableDatabase
            AppDatabase.MIGRATION_10_11.migrate(db)

            db.query("SELECT uuid, title, currentTime, speed FROM library_items").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("u1", cursor.getString(0))
                assertEquals("Book A", cursor.getString(1))
                assertEquals(12.5, cursor.getDouble(2), 0.0)
                assertTrue(cursor.isNull(3))
                assertEquals(1, cursor.count)
            }
            db.execSQL("UPDATE library_items SET speed = 1.5 WHERE uuid = 'u1'")
            db.query("SELECT speed FROM library_items WHERE uuid = 'u1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1.5, cursor.getDouble(0), 0.0)
            }
        }
    }
}
