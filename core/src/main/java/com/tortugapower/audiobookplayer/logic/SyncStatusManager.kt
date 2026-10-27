package com.tortugapower.audiobookplayer.logic

import androidx.annotation.VisibleForTesting
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

    /**
     * A download that failed for good and was dropped (iOS's download error): the app says so once.
     * [expiredServer] names the media server that rejected the sign-in: trying again won't help until the
     * user signs in again, so the app says that instead.
     */
    data class DownloadFailure(val uuid: String, val title: String, val expiredServer: String? = null)

    private val _downloadFailures = MutableStateFlow<List<DownloadFailure>>(emptyList())
    /**
     * The dropped downloads the user hasn't been told about yet, oldest first. They wait here (a rotation, or
     * the app away from the screen) until the app shows them and hands them to [dismissDownloadFailures].
     */
    val downloadFailures: StateFlow<List<DownloadFailure>> = _downloadFailures.asStateFlow()

    fun notifyDownloadFailed(failure: DownloadFailure) {
        _downloadFailures.update { it + failure }
    }

    /** [shown] were shown (the oldest pending ones); any that came in since stay, and a repeated call does nothing */
    fun dismissDownloadFailures(shown: List<DownloadFailure>) {
        _downloadFailures.update { if (it.take(shown.size) == shown) it.drop(shown.size) else it }
    }

    // How often one library level's contents (and the sort-preferences pull that rides the same cadence)
    // may be fetched: 60 s, matching iOS's per-level list sync throttle.
    internal const val FETCH_THROTTLE_MS = 60_000L

    // Test seam for the fetch throttles (same idea as StorageMonitor.availableBytesProvider).
    @VisibleForTesting
    @Volatile
    internal var clock: () -> Long = { System.currentTimeMillis() }

    /**
     * Forgets every fetch timestamp: a lapse lets the listings and the preferences pull it held back run as soon
     * as the account is back (SyncQueueReset). Tests use it so a test clock can't leave a future stamp behind.
     */
    internal fun resetFetchThrottles() {
        _lastPathFetchTimestamps.value = emptyMap()
        synchronized(this) { lastFetchPreferencesTimestamp = 0L }
    }

    // Map of relativePath to last fetch timestamp
    private val _lastPathFetchTimestamps = MutableStateFlow<Map<String, Long>>(emptyMap())

    fun updateLastSyncTimestamp(timestamp: Long) {
        _lastSyncTimestamp.value = timestamp
    }

    fun canFetchContents(path: String): Boolean {
        val lastFetch = _lastPathFetchTimestamps.value[path] ?: 0L
        return (clock() - lastFetch) > FETCH_THROTTLE_MS
    }

    fun markPathAsFetched(path: String) {
        _lastPathFetchTimestamps.update { it + (path to clock()) }
    }

    fun checkAndMarkFetchContents(path: String): Boolean {
        var allowed = false
        val now = clock()
        _lastPathFetchTimestamps.update { map ->
            val lastFetch = map[path] ?: 0L
            if ((now - lastFetch) > FETCH_THROTTLE_MS) {
                allowed = true
                map + (path to now)
            } else {
                allowed = false
                map
            }
        }
        return allowed
    }

    // Debounce for pulling user preferences (sort rules) — same throttle as the contents fetch.
    private var lastFetchPreferencesTimestamp: Long = 0L

    /** A forced pull just ran: start the cooldown so a regular pull right after it (a library visit) is skipped. */
    @Synchronized
    fun markFetchPreferences() {
        lastFetchPreferencesTimestamp = clock()
    }

    @Synchronized
    fun checkAndMarkFetchPreferences(): Boolean {
        val now = clock()
        if ((now - lastFetchPreferencesTimestamp) > FETCH_THROTTLE_MS) {
            lastFetchPreferencesTimestamp = now
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
