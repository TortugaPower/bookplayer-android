package com.tortugapower.audiobookplayer.logic

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * When the sync service is started to keep the process alive: each time a lane gets a worker. Android stops a
 * backgrounded app otherwise, and the engine with it. The service stops itself once the lanes have been idle a
 * minute (TaskConcurrencyServiceHost).
 */
internal object SyncKeepAlive {
    suspend fun startWhenLanesWork(activeQueues: Flow<Set<String>>, startService: () -> Unit) {
        activeQueues.map { it.isNotEmpty() }.distinctUntilChanged().filter { it }.collect { startService() }
    }
}
