package com.tortugapower.audiobookplayer.logic

import io.sentry.SentryOptions
import io.sentry.android.core.internal.threaddump.Lines
import io.sentry.android.core.internal.threaddump.ThreadDumpParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

/**
 * Why we are on sentry-android >= 8.54: ANR events are built from the system's thread dump, and R8's
 * `-repackageclasses` (in the app since 1.2.0) leaves most obfuscated classes in the default package.
 * Older parsers built the frame's module as `"$package.$class"` with a null package, so those frames
 * arrived in Sentry as `null.uf` and the uploaded mapping could not resolve them (the 1.2.0+21 ANR
 * ANDROID-BOOKPLAYER-2C is unreadable for exactly this reason). A default-package frame must keep its
 * bare class name as the module so symbolication can find it in the mapping.
 */
class SentryAnrThreadDumpTest {

    private val dump = """
        |"main" prio=5 tid=1 Runnable
        |  | group="main" sCount=0 ucsCount=0 flags=0 obj=0x72a3c5d8 self=0x7b8c4d6c00
        |  | sysTid=4321 nice=-10 cgrp=top-app sched=0/0 handle=0x7c1d5c14f8
        |  at android.os.Trace.beginSection(Trace.java:285)
        |  at l53.invoke(SourceFile:12)
        |  at uf.doFrame(SourceFile:4)
        |  at android.view.Choreographer.doFrame(Choreographer.java:907)
        |  at com.tortugapower.audiobookplayer.MainActivity.onResume(SourceFile:10)
        |  at android.os.Looper.loop(Looper.java:334)
        |
    """.trimMargin()

    private fun parsedModules(): List<String> {
        val parser = ThreadDumpParser(SentryOptions(), /* isBackground = */ false)
        parser.parse(Lines.readLines(BufferedReader(StringReader(dump))))
        val main = parser.threads.single { it.name == "main" }
        return main.stacktrace!!.frames!!.map { it.module!! }
    }

    @Test
    fun defaultPackageFramesKeepTheirBareClassName() {
        val modules = parsedModules()
        assertTrue("expected the repackaged frame as a bare class name, got $modules", "uf" in modules)
        assertTrue("expected the repackaged lambda as a bare class name, got $modules", "l53" in modules)
        assertTrue("no frame may carry the null-package prefix: $modules", modules.none { it.startsWith("null.") })
    }

    @Test
    fun packagedFramesAreUnchanged() {
        val modules = parsedModules()
        assertEquals(6, modules.size)
        assertTrue("com.tortugapower.audiobookplayer.MainActivity" in modules)
        assertTrue("android.view.Choreographer" in modules)
    }
}
