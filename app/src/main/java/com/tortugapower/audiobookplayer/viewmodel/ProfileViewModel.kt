package com.tortugapower.audiobookplayer.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tortugapower.audiobookplayer.BookPlayerApplication
import com.tortugapower.audiobookplayer.database.entities.AccountEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import com.tortugapower.audiobookplayer.logic.ListeningStatsCalculator
import com.tortugapower.audiobookplayer.logic.PlaybackManager
import com.tortugapower.audiobookplayer.logic.QueuedTaskSection
import com.tortugapower.audiobookplayer.logic.SubscriptionManager
import com.tortugapower.audiobookplayer.logic.SyncEngineWaker
import com.tortugapower.audiobookplayer.logic.SyncFailurePolicy
import com.tortugapower.audiobookplayer.logic.SyncPauseReport
import com.tortugapower.audiobookplayer.logic.SyncStatusManager
import com.tortugapower.audiobookplayer.logic.SyncTaskFactory
import com.tortugapower.audiobookplayer.logic.UploadFilePayload
import com.tortugapower.audiobookplayer.logic.UploadHandBack
import com.tortugapower.audiobookplayer.logic.pause
import com.tortugapower.audiobookplayer.logic.groupedByLane
import com.tortugapower.audiobookplayer.network.NetworkClient
import com.tortugapower.audiobookplayer.repository.AccountRepository
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProfileViewModel(
    private val accountRepository: AccountRepository,
    private val syncTaskRepository: SyncTaskRepository,
    private val statisticsDao: com.tortugapower.audiobookplayer.database.dao.StatisticsDao,
    private val libraryDao: com.tortugapower.audiobookplayer.database.dao.LibraryDao,
    /** Ends the library sync's session: nothing running can queue into, or mark, the next account's library */
    private val endSyncSession: suspend () -> Unit,
) : ViewModel() {

    val account: StateFlow<AccountEntity?> = accountRepository.getAccountFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    val totalPlaytime: StateFlow<Long> = statisticsDao.getTotalPlaytimeFlow()
        .map { it ?: 0L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val completedBooks: StateFlow<Int> = libraryDao.getCompletedBooksCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val daysListened: StateFlow<Int> = statisticsDao.getDaysListenedFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val mostListenedBookArtwork: StateFlow<String?> = statisticsDao.getMostListenedBookArtworkFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // The card only needs today's sessions. The query cutoff is fixed at the creation day's
    // midnight, which stays a superset of the mapper's live "today" filter as time moves on.
    val todayListenedTime: StateFlow<Long> =
        statisticsDao.getSessionsSince(ListeningStatsCalculator.startOfDay(System.currentTimeMillis()))
            .map { sessions -> ListeningStatsCalculator.todayListenedTime(sessions, System.currentTimeMillis()) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val syncTasks: StateFlow<List<SyncTaskEntity>> = syncTaskRepository.getAllTasks()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /**
     * The Queued Tasks screen's lanes, grouped off the main thread (a first sync can queue thousands).
     * Null until the queue is first read, so the screen never shows an empty queue that isn't; read from
     * Room directly, since [syncTasks] starts with a placeholder empty list.
     */
    val queuedTaskSections: StateFlow<List<QueuedTaskSection>?> = syncTaskRepository.getAllTasks()
        .map { it.groupedByLane() }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Parked tasks across every lane: the Profile entry turns into a warning while any need the user */
    val pausedTasksCount: StateFlow<Int> = syncTasks.map { tasks -> tasks.count { it.pause != null } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val pendingTasksCount: StateFlow<Int> = syncTasks.map { tasks ->
        tasks.count { it.status != SyncTaskStatus.COMPLETED }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val lastSyncTimestamp: StateFlow<Long?> = SyncStatusManager.lastSyncTimestamp
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val taskProgress: StateFlow<Map<String, Double>> = SyncStatusManager.taskProgress
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun logout() {
        viewModelScope.launch {
            clearLocalSession()
        }
    }

    /**
     * Permanently delete the account on the server (DELETE /v1/user/delete, authenticated via
     * the Bearer token), then clear the local session. Returns the server's confirmation message
     * on success, or a failure the caller can surface. The local data is wiped only after the
     * server confirms deletion.
     */
    suspend fun deleteAccount(): Result<String?> {
        return try {
            val response = NetworkClient.authApi.deleteAccount()
            if (response.isSuccessful) {
                // The server's confirmation message, or null so the UI shows its localized default.
                val message = response.body()?.message
                clearLocalSession()
                Result.success(message)
            } else {
                Result.failure(Exception("Failed to delete account (${response.code()})"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun clearLocalSession() {
        // Drop the in-memory Bearer token first, before any suspension point, so it's reliably
        // cleared even if a later step throws or the coroutine is cancelled. Done here (not just in
        // the delete path) so logout clears it too. None of the steps below need the token.
        NetworkClient.setToken(null)
        endSyncSession()
        accountRepository.deleteAccount()
        syncTaskRepository.deleteAllTasks() // also clears any queued preference push/fetch tasks
        // Drop every local library_sort:* preference so the next login pulls fresh (no stale state).
        // runCatching like LibraryViewModel's sortManager access: unit tests with a plain
        // Application have no singleton, and logout cleanup must not abort halfway.
        runCatching { BookPlayerApplication.instance.librarySortManager.clearLocalPreferences() }
        SubscriptionManager.logout()
        SyncStatusManager.updateLastSyncTimestamp(0) // Reset to effectively "Never"
        // AFTER the account row is gone: stop + unload the loaded book if it's now gate-blocked
        // (remote item, no local file). Without this the live session keeps streaming off its
        // still-valid presigned URL — and, because the media service outlives an app swipe, would
        // even resume on the next launch without re-entering any gated load path.
        PlaybackManager.enforceRemoteStreamingGate(com.tortugapower.audiobookplayer.core.CoreContext.appContext)
    }

    /**
     * The user's Retry: back to pending (one account pause resumes them all), and the engine is woken. An
     * upload's Retry asks for the book again: it may be registered again even if it already was this session.
     */
    fun retryPausedTask(task: SyncTaskEntity) {
        if (task.jobType == SyncTaskFactory.JOB_UPLOAD_FILE) {
            UploadFilePayload.uuid(task.payload)?.let(UploadHandBack::release)
        }
        viewModelScope.launch {
            syncTaskRepository.resumeTask(task.id)
            SyncEngineWaker.notifyWorkEnqueued()
        }
    }

    /** Only a book over the upload limit can be dismissed: retrying can't make it smaller */
    fun dismissPausedTask(task: SyncTaskEntity) {
        if (task.pause?.errorCode != SyncFailurePolicy.FILE_TOO_LARGE) return
        viewModelScope.launch { syncTaskRepository.deleteTask(task) }
    }

    /** What Report sends for [task], read now from the queue, the library and the account */
    suspend fun pauseReport(task: SyncTaskEntity): SyncPauseReport = withContext(Dispatchers.IO) {
        SyncPauseReport(
            pausedTask = task,
            queuedTasks = syncTaskRepository.getAllTasks().first(),
            library = libraryDao.getAllItemsSync().mapNotNull { item -> item.relativePath?.let { it to item.uuid } },
            appVersion = SyncPauseReport.appVersion(accountRepository.getAccount()?.tier),
            device = SyncPauseReport.device(),
        )
    }
}
