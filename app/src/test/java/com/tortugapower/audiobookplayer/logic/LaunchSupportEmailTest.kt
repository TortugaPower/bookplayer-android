package com.tortugapower.audiobookplayer.logic

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The support-email launch never crashes on a device whose mail app is gone: the missing-app failure
 * becomes `false`, which the settings screen turns into the clipboard fallback it already shows when
 * no mail app was installed to begin with (ANDROID-BOOKPLAYER-1P/-1R's crash class, raw-intent form).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LaunchSupportEmailTest {

    // The screen launches from its Activity (LocalContext.current); an Application context would need NEW_TASK.
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val context: Context get() = activity

    private fun composerFor(pkg: String) = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        setPackage(pkg)
    }

    private fun installMailApp(pkg: String) {
        val component = ComponentName(pkg, "$pkg.Compose")
        shadowOf(context.packageManager).addActivityIfNotPresent(component)
        shadowOf(context.packageManager).addIntentFilterForActivity(
            component,
            // What a mail app's manifest declares; implicit-intent resolution needs the DEFAULT category.
            IntentFilter(Intent.ACTION_SEND).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataType("text/plain")
            },
        )
    }

    /** The platform always resolves ACTION_CHOOSER to its own picker; the shadow has to be told. */
    private fun installSystemChooser() {
        val component = ComponentName("android", "com.android.internal.app.ChooserActivity")
        shadowOf(context.packageManager).addActivityIfNotPresent(component)
        shadowOf(context.packageManager).addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_CHOOSER).apply { addCategory(Intent.CATEGORY_DEFAULT) },
        )
    }

    @Before fun setUp() {
        // Make startActivity behave like the platform: an intent nothing resolves throws.
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).checkActivities(true)
    }

    @Test
    fun noMailApp_returnsFalse_andStartsNothing() {
        assertFalse(launchSupportEmail(context, emptyList()))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun oneMailApp_startsItsComposer() {
        installMailApp("com.example.mail")

        assertTrue(launchSupportEmail(context, listOf(composerFor("com.example.mail"))))
        assertEquals("com.example.mail", shadowOf(activity).nextStartedActivity?.`package`)
    }

    @Test
    fun mailAppGoneSinceItResolved_returnsFalse_insteadOfThrowing() {
        // Resolved a moment ago, uninstalled since: nothing registered for the package now.
        assertFalse(launchSupportEmail(context, listOf(composerFor("com.example.gone"))))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun severalMailApps_startTheEmailOnlyPicker() {
        installMailApp("com.example.mail")
        installMailApp("com.example.other")
        installSystemChooser()

        assertTrue(launchSupportEmail(context, listOf(composerFor("com.example.mail"), composerFor("com.example.other"))))
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, started?.action)
        assertEquals(1, started?.getParcelableArrayExtra(Intent.EXTRA_INITIAL_INTENTS)?.size)
    }
}
