package com.tortugapower.audiobookplayer.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins MIGRATION_8_9's two effects against a minimal v8-shaped fixture (exportSchema=false rules
 * out MigrationTestHelper — same approach as Migration7To8Test):
 *  1. external_servers gains the nullable stableId column;
 *  2. this device's external_resources rows that carried a LOCAL rowid as hostId are rewritten to
 *     the canonical URL key of the server they pointed at — resolution no longer understands
 *     rowids, so without the rewrite already-imported books stop resolving on the device where
 *     they work today.
 */
@RunWith(RobolectricTestRunner::class)
class Migration8To9Test {

    @Test
    fun migration_addsStableIdColumn_andRewritesRowidHostIdsToCanonicalKeys() {
        val config = SupportSQLiteOpenHelper.Configuration.builder(
            ApplicationProvider.getApplicationContext<Context>()
        )
            .name(null) // in-memory
            .callback(object : SupportSQLiteOpenHelper.Callback(8) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE external_servers (id INTEGER NOT NULL PRIMARY KEY, url TEXT NOT NULL)"
                    )
                    db.execSQL(
                        "CREATE TABLE external_resources (id INTEGER NOT NULL PRIMARY KEY, hostId TEXT)"
                    )
                    // Two servers; note server 2's URL carries a default port + trailing slash the
                    // canonical key strips.
                    db.execSQL("INSERT INTO external_servers VALUES (1, 'https://abs.example.com')")
                    db.execSQL("INSERT INTO external_servers VALUES (2, 'https://jf.example.com:443/')")
                    db.execSQL(
                        """INSERT INTO external_resources VALUES
                            (10, '1'),
                            (11, '2'),
                            (12, 'already-a-guid'),
                            (13, NULL)"""
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()

        FrameworkSQLiteOpenHelperFactory().create(config).use { helper ->
            val db = helper.writableDatabase
            AppDatabase.MIGRATION_8_9.migrate(db)

            // Column exists and is writable.
            db.execSQL("UPDATE external_servers SET stableId = 'guid-x' WHERE id = 1")

            val expected = mapOf(
                10L to "https://abs.example.com",
                11L to "https://jf.example.com",
                12L to "already-a-guid",
                13L to null,
            )
            db.query("SELECT id, hostId FROM external_resources").use { cursor ->
                var rows = 0
                while (cursor.moveToNext()) {
                    rows++
                    val id = cursor.getLong(0)
                    val hostId = if (cursor.isNull(1)) null else cursor.getString(1)
                    assertEquals("row $id", expected.getValue(id), hostId)
                }
                assertEquals(expected.size, rows)
            }
        }
    }
}
