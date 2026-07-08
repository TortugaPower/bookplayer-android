package com.tortugapower.audiobookplayer.logic

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Plain android.app.Application: BookPlayerApplication wires networking/RevenueCat in onCreate,
// none of which these Intent-shape tests need.
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class ShortcutHelperTest {

    @Test
    fun testGetShortcutId() {
        val uuid = "1234-abcd"
        val expected = "shortcut_play_1234-abcd"
        assertEquals(expected, ShortcutHelper.getShortcutId(uuid))
    }

    @Test
    fun testGetShortcutIntentUri() {
        val uuid = "1234-abcd"
        val expected = "bookplayer://play?identifier=1234-abcd&autoplay=true"
        assertEquals(expected, ShortcutHelper.getShortcutIntentUri(uuid))
    }

    // Guards the anti-hijack invariant: the pinned-shortcut intent must explicitly target
    // MainActivity. An implicit ACTION_VIEW on the bookplayer:// scheme could be intercepted
    // by any app registering the same scheme, leaking the item uuid or hijacking the launch.
    @Test
    fun testBuildShortcutIntentTargetsMainActivityExplicitly() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val intent = ShortcutHelper.buildShortcutIntent(context, "1234-abcd")

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("bookplayer://play?identifier=1234-abcd&autoplay=true", intent.data.toString())
        assertEquals(context.packageName, intent.component?.packageName)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
    }
}
