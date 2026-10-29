package com.tortugapower.audiobookplayer.logic

import android.Manifest
import android.app.Application
import android.os.Build
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
}
