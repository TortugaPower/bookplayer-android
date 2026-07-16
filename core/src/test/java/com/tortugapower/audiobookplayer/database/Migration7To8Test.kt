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
 * Pins MIGRATION_7_8's data-touching SQL: percentCompleted rows written on the API's 0..100 scale
 * (pre-fix server fetches) are normalized to the canonical 0..1 fraction, while rows already in
 * range stay byte-identical — including the 1.0 boundary (a finished book, NOT "1%").
 *
 * exportSchema is false in this project, so Room's MigrationTestHelper (which replays exported
 * schema JSONs) can't be used; the migration runs against a minimal v7-shaped fixture instead —
 * the UPDATE only touches the one column, so the fixture only needs that column to exist.
 */
@RunWith(RobolectricTestRunner::class)
class Migration7To8Test {

    @Test
    fun migration_normalizesLegacyServerScaleRows_leavesFractionsUntouched() {
        val config = SupportSQLiteOpenHelper.Configuration.builder(
            ApplicationProvider.getApplicationContext<Context>()
        )
            .name(null) // in-memory
            .callback(object : SupportSQLiteOpenHelper.Callback(7) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE library_items (uuid TEXT NOT NULL PRIMARY KEY, percentCompleted REAL NOT NULL)"
                    )
                    db.execSQL(
                        """INSERT INTO library_items VALUES
                            ('finished-from-server', 100.0),
                            ('midway-from-server', 45.0),
                            ('local-fraction', 0.5),
                            ('finished-local', 1.0),
                            ('unstarted', 0.0)"""
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()

        FrameworkSQLiteOpenHelperFactory().create(config).use { helper ->
            val db = helper.writableDatabase
            AppDatabase.MIGRATION_7_8.migrate(db)

            val expected = mapOf(
                "finished-from-server" to 1.0,
                "midway-from-server" to 0.45,
                "local-fraction" to 0.5,
                "finished-local" to 1.0,
                "unstarted" to 0.0,
            )
            db.query("SELECT uuid, percentCompleted FROM library_items").use { cursor ->
                var rows = 0
                while (cursor.moveToNext()) {
                    rows++
                    val uuid = cursor.getString(0)
                    assertEquals(uuid, expected.getValue(uuid), cursor.getDouble(1), 1e-9)
                }
                assertEquals(expected.size, rows)
            }
        }
    }
}
