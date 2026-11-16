package com.tortugapower.audiobookplayer.logic

import android.Manifest
import android.app.Application
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The notification permission is asked once, on Android 13+, and only while it isn't granted: asking again after a
 * denial would nag on every transfer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class NotificationPermissionPromptTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private var requests = 0

    @Before fun neverAsked() = runBlocking {
        context.dataStore.edit { it.remove(NotificationPermissionPrompt.ASKED) }
        Unit
    }

    @Test fun theFirstTransfer_asks_andTheNextOnesDont() = runBlocking {
        NotificationPermissionPrompt.maybeAsk(context, Build.VERSION_CODES.TIRAMISU) { requests++ }
        NotificationPermissionPrompt.maybeAsk(context, Build.VERSION_CODES.TIRAMISU) { requests++ }

        assertEquals(1, requests)
    }

    @Test fun alreadyGranted_doesntAsk() = runBlocking {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        NotificationPermissionPrompt.maybeAsk(context, Build.VERSION_CODES.TIRAMISU) { requests++ }

        assertEquals(0, requests)
    }

    /** Before Android 13 notifications need no permission */
    @Test fun beforeAndroid13_doesntAsk() = runBlocking {
        NotificationPermissionPrompt.maybeAsk(context, Build.VERSION_CODES.S_V2) { requests++ }

        assertEquals(0, requests)
    }

    /** A download can fill the disk just as the prompt would show: no crash, no ask, and the next transfer asks */
    @Test fun aFullDisk_skipsTheAsk_untilTheNextTransfer() = runBlocking {
        val noSpace = IOException("write failed: ENOSPC (No space left on device)")
        var reported: IOException? = null

        NotificationPermissionPrompt.maybeAsk(context, Build.VERSION_CODES.TIRAMISU, FailingWrites(noSpace), { reported = it; true }) { requests++ }
        assertEquals(0, requests)
        assertSame(noSpace, reported)

        NotificationPermissionPrompt.maybeAsk(context, Build.VERSION_CODES.TIRAMISU) { requests++ }
        assertEquals(1, requests)
    }

    @Test fun anyOtherStoreFailure_isABug() {
        val broken = IOException("not a full disk")

        val thrown = assertThrows(IOException::class.java) {
            runBlocking {
                NotificationPermissionPrompt.maybeAsk(context, Build.VERSION_CODES.TIRAMISU, FailingWrites(broken), { false }) { requests++ }
            }
        }

        assertSame(broken, thrown)
        assertEquals(0, requests)
    }

    /** A store that reads as never asked, and whose writes fail */
    private class FailingWrites(private val failure: IOException) : DataStore<Preferences> {
        override val data: Flow<Preferences> = flowOf(emptyPreferences())
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw failure
    }
}
