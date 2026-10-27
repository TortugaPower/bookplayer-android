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
 * Pins MIGRATION_10_11 against a minimal v10-shaped fixture (exportSchema=false rules out
 * MigrationTestHelper — same approach as Migration9To10Test): ABS rows lose the "server-settings"
 * stableId every ABS server reported, while real Jellyfin ids and the hostIds already written on
 * external resources stay as they are.
 */
@RunWith(RobolectricTestRunner::class)
class Migration10To11Test {

    @Test
    fun migration_clearsTheAbsConstant_only() {
        val config = SupportSQLiteOpenHelper.Configuration.builder(
            ApplicationProvider.getApplicationContext<Context>()
        )
            .name(null) // in-memory
            .callback(object : SupportSQLiteOpenHelper.Callback(10) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE external_servers (id INTEGER NOT NULL PRIMARY KEY, type TEXT NOT NULL, url TEXT NOT NULL, stableId TEXT)"
                    )
                    db.execSQL("INSERT INTO external_servers VALUES (1, 'AUDIOBOOKSHELF', 'http://abs-a.example.com', 'server-settings')")
                    db.execSQL("INSERT INTO external_servers VALUES (2, 'AUDIOBOOKSHELF', 'http://abs-b.example.com', 'server-settings')")
                    db.execSQL("INSERT INTO external_servers VALUES (3, 'JELLYFIN', 'https://jf.example.com', 'jf-guid')")
                    // Pathological: the type condition keeps the rewrite ABS-only.
                    db.execSQL("INSERT INTO external_servers VALUES (4, 'JELLYFIN', 'https://jf2.example.com', 'server-settings')")
                    db.execSQL("CREATE TABLE external_resources (id INTEGER NOT NULL PRIMARY KEY, hostId TEXT)")
                    db.execSQL("INSERT INTO external_resources VALUES (1, 'server-settings')")
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()

        FrameworkSQLiteOpenHelperFactory().create(config).use { helper ->
            val db = helper.writableDatabase
            AppDatabase.MIGRATION_10_11.migrate(db)

            val stableIds = db.query("SELECT id, stableId FROM external_servers ORDER BY id").use { cursor ->
                buildMap { while (cursor.moveToNext()) put(cursor.getLong(0), cursor.getString(1)) }
            }
            assertEquals(mapOf(1L to null, 2L to null, 3L to "jf-guid", 4L to "server-settings"), stableIds)

            // Resources keep the constant: those books go to support.
            db.query("SELECT hostId FROM external_resources").use { cursor ->
                cursor.moveToFirst()
                assertEquals("server-settings", cursor.getString(0))
            }
        }
    }
}
