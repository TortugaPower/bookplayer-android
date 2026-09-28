package com.tortugapower.audiobookplayer.debug

import android.content.Context

/** Production twin of the dev-flavor knobs: nothing is ever overridden. */
object DebugKnobs {
    @Suppress("UNUSED_PARAMETER")
    fun mediaForegroundTimeoutMs(context: Context): Long? = null
}
