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

    // Cooperative-cancellation registry for in-flight downloads (keyed by taskID = item uuid). A running
    // DownloadFileProcessor polls [isCancelRequested] in its read loop and aborts (the OkHttp stream loop
    // otherwise runs to completion — deleting the DB row only stops a still-queued download).
    private val _cancelRequests = MutableStateFlow<Set<String>>(emptySet())

    fun requestCancel(taskId: String) { _cancelRequests.update { it + taskId } }
    fun isCancelRequested(taskId: String): Boolean = _cancelRequests.value.contains(taskId)
    fun clearCancel(taskId: String) { _cancelRequests.update { it - taskId } }

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

    fun checkAndMarkFetchContents(path: String): Boolean {
        var allowed = false
        _lastPathFetchTimestamps.update { map ->
            val lastFetch = map[path] ?: 0L
            if ((System.currentTimeMillis() - lastFetch) > 30_000) {
                allowed = true
                map + (path to System.currentTimeMillis())
            } else {
                allowed = false
                map
            }
        }
        return allowed
    }

    @Synchronized
    fun checkAndMarkSyncIdentifiers(): Boolean {
        if (canSyncIdentifiers()) {
            markIdentifiersAsSynced()
            return true
        }
        return false
    }

    // Debounce for pulling user preferences (sort rules) — same 30s throttle as contents fetch.
    private var lastFetchPreferencesTimestamp: Long = 0L

    @Synchronized
    fun checkAndMarkFetchPreferences(): Boolean {
        if ((System.currentTimeMillis() - lastFetchPreferencesTimestamp) > 30_000) {
            lastFetchPreferencesTimestamp = System.currentTimeMillis()
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
