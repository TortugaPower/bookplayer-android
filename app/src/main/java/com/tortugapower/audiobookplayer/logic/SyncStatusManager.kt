package com.tortugapower.audiobookplayer.logic

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object SyncStatusManager {
    private val _lastSyncTimestamp = MutableStateFlow<Long?>(null)
    val lastSyncTimestamp: StateFlow<Long?> = _lastSyncTimestamp.asStateFlow()

    // Map of taskId to progress (0.0 to 1.0)
    private val _taskProgress = MutableStateFlow<Map<String, Double>>(emptyMap())
    val taskProgress: StateFlow<Map<String, Double>> = _taskProgress.asStateFlow()

    // Map of relativePath to last fetch timestamp
    private val _lastPathFetchTimestamps = MutableStateFlow<Map<String, Long>>(emptyMap())
    
    // Last timestamp for account-wide sync (identifiers)
    private var lastSyncIdentifiersTimestamp: Long = 0L

    fun updateLastSyncTimestamp(timestamp: Long) {
        _lastSyncTimestamp.value = timestamp
    }

    fun canFetchContents(path: String): Boolean {
        val lastFetch = _lastPathFetchTimestamps.value[path] ?: 0L
        return (System.currentTimeMillis() - lastFetch) > 30_000 // 30 seconds throttle
    }

    fun markPathAsFetched(path: String) {
        _lastPathFetchTimestamps.update { it + (path to System.currentTimeMillis()) }
    }

    fun canSyncIdentifiers(): Boolean {
        return (System.currentTimeMillis() - lastSyncIdentifiersTimestamp) > 30_000
    }

    fun markIdentifiersAsSynced() {
        lastSyncIdentifiersTimestamp = System.currentTimeMillis()
    }

    @Synchronized
    fun checkAndMarkFetchContents(path: String): Boolean {
        if (canFetchContents(path)) {
            markPathAsFetched(path)
            return true
        }
        return false
    }

    @Synchronized
    fun checkAndMarkSyncIdentifiers(): Boolean {
        if (canSyncIdentifiers()) {
            markIdentifiersAsSynced()
            return true
        }
        return false
    }

    fun updateTaskProgress(taskId: String, progress: Double) {
        _taskProgress.update { it + (taskId to progress) }
    }

    fun clearTaskProgress(taskId: String) {
        _taskProgress.update { it - taskId }
    }
    
    fun clearAllProgress() {
        _taskProgress.value = emptyMap()
    }
}
