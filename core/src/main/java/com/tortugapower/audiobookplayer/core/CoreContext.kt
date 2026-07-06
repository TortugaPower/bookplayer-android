package com.tortugapower.audiobookplayer.core

import android.content.Context

/**
 * Application context holder for the shared :core module, set by the host app (phone or Wear) at
 * startup. Lets :core code that needs a Context (e.g. external-service device-id storage) avoid
 * depending on the app-module Application singleton. Prefer passing Context explicitly where practical.
 */
object CoreContext {
    lateinit var appContext: Context
        private set

    fun init(context: Context) { appContext = context.applicationContext }

    fun isInitialized(): Boolean = ::appContext.isInitialized
}
