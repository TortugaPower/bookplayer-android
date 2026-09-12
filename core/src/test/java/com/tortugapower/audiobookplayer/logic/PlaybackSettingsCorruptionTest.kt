package com.tortugapower.audiobookplayer.logic

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Sentry ANDROID-BOOKPLAYER-13: a `playback_settings.preferences_pb` that no longer parses (a zero-filled
 * or truncated proto, typically left behind by a disk-full or interrupted write) must reset to defaults,
 * not throw `CorruptionException` on every launch until the user reinstalls.
 *
 * The store is a process-wide delegate that reads its file once, so this class runs alone in its own
 * Robolectric sandbox (`sdk = 31`, distinct from every other test's config) and corrupts the file
 * before the very first access.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class PlaybackSettingsCorruptionTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun corruptPreferencesFile_resetsToDefaults_andStoreStaysWritable() = runBlocking {
        val file = File(context.filesDir, "datastore/playback_settings.preferences_pb")
        file.parentFile!!.mkdirs()
        // "Protocol message contained an invalid tag (zero)" — the exact shape seen in production.
        file.writeBytes(ByteArray(64))

        // First access: must recover, not crash.
        assertEquals(1.0f, PlaybackSettingsManager.getSpeed(context).first())

        // The store must be fully usable afterwards (the corrupt file was replaced, not just skipped).
        PlaybackSettingsManager.setSpeed(context, 1.5f)
        assertEquals(1.5f, PlaybackSettingsManager.getSpeed(context).first())
        assertTrue("preferences file should have been rewritten", file.length() > 0 && file.readBytes().any { it != 0.toByte() })
    }
}
