package com.tortugapower.audiobookplayer.logic

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * When the sync service is started to keep the process alive: each time the lanes go from idle to busy (not for
 * every lane that gets a worker: the service is already up), and again on a new task or an app open while lanes
 * work. Android stops a backgrounded app otherwise, and the engine with it. A start can fail (refused from the
 * background, or the dataSync budget spent) and lanes can stay busy for hours (one waiting out a backoff), so the
 * change from idle to busy alone isn't enough. The service stops itself once the lanes have been idle a minute
 * (TaskConcurrencyServiceHost), and a start while it runs costs nothing.
 */
internal object SyncKeepAlive {
    suspend fun startWhenLanesWork(activeQueues: Flow<Set<String>>, startService: () -> Unit) {
        activeQueues.map { it.isNotEmpty() }.distinctUntilChanged().filter { it }.collect { startService() }
    }

    /** A new task or an app open: the service is started again if lanes work (a lane going busy starts it anyway) */
    fun startIfLanesWork(activeQueues: Set<String>, startService: () -> Unit) {
        if (activeQueues.isNotEmpty()) startService()
    }
}
