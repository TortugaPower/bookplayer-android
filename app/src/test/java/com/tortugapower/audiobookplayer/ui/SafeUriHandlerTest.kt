package com.tortugapower.audiobookplayer.ui

import android.content.ActivityNotFoundException
import androidx.compose.ui.platform.UriHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Sentry ANDROID-BOOKPLAYER-1P / -1R: a tap on a contributor's GitHub link killed the app on a Pixel 3
 * whose browser was disabled. Compose's platform [UriHandler] throws when nothing handles the URI, and
 * nine call sites used it bare. The wrapper turns that one failure into a callback and lets every other
 * failure through unchanged.
 */
class SafeUriHandlerTest {

    private class RecordingDelegate(private val failure: Throwable? = null) : UriHandler {
        val opened = mutableListOf<String>()
        override fun openUri(uri: String) {
            opened += uri
            failure?.let { throw it }
        }
    }

    @Test
    fun `a URI the platform can open is delegated once and reports nothing`() {
        val delegate = RecordingDelegate()
        val reported = mutableListOf<String>()
        SafeUriHandler(delegate) { reported += it }.openUri("https://github.com/TortugaPower")

        assertEquals(listOf("https://github.com/TortugaPower"), delegate.opened)
        assertEquals(emptyList<String>(), reported)
    }

    @Test
    fun `no activity to handle the URI is reported, not thrown`() {
        val delegate = RecordingDelegate(ActivityNotFoundException("No Activity found to handle Intent"))
        val reported = mutableListOf<String>()
        SafeUriHandler(delegate) { reported += it }.openUri("https://github.com/Hirobreak")

        assertEquals(listOf("https://github.com/Hirobreak"), reported)
    }

    @Test
    fun `the platform handler wraps the missing activity in IllegalArgumentException, which is unwrapped`() {
        // AndroidUriHandler.openUri rethrows ActivityNotFoundException as IllegalArgumentException("Can't open $uri"),
        // which is the exact shape in the Sentry report.
        val wrapped = IllegalArgumentException("Can't open https://github.com/Hirobreak.", ActivityNotFoundException())
        val reported = mutableListOf<String>()
        SafeUriHandler(RecordingDelegate(wrapped)) { reported += it }.openUri("https://github.com/Hirobreak")

        assertEquals(listOf("https://github.com/Hirobreak"), reported)
    }

    @Test
    fun `a missing activity buried deeper in the causal chain is still recognised`() {
        // Today the platform wraps once. A Compose release that adds a level would otherwise bring the crash back.
        val buried = IllegalArgumentException("Can't open", RuntimeException("wrapped again", ActivityNotFoundException()))
        val reported = mutableListOf<String>()
        SafeUriHandler(RecordingDelegate(buried)) { reported += it }.openUri("https://example.org")

        assertEquals(listOf("https://example.org"), reported)
    }

    @Test(timeout = 2_000)
    fun `a cyclic cause chain is rethrown, not walked forever`() {
        // `initCause` can build a cycle; an unbounded walk would spin on the main thread instead of rethrowing.
        val a = IllegalArgumentException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        val handler = SafeUriHandler(RecordingDelegate(a)) { throw AssertionError("must not be reported: $it") }

        assertThrows(IllegalArgumentException::class.java) { handler.openUri("https://example.org") }
    }

    @Test
    fun `any other failure is not swallowed`() {
        // A wrapper that ate every IllegalArgumentException would hide a malformed URI built by our own code.
        val reported = mutableListOf<String>()
        val handler = SafeUriHandler(RecordingDelegate(IllegalArgumentException("not a URI"))) { reported += it }

        assertThrows(IllegalArgumentException::class.java) { handler.openUri("::garbage") }
        assertEquals(emptyList<String>(), reported)
    }
}
