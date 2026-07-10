package com.tortugapower.audiobookplayer.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tortugapower.audiobookplayer.database.entities.AccountEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import com.tortugapower.audiobookplayer.logic.ListeningStatsCalculator
import com.tortugapower.audiobookplayer.logic.PlaybackManager
import com.tortugapower.audiobookplayer.logic.SubscriptionManager
import com.tortugapower.audiobookplayer.logic.SyncStatusManager
import com.tortugapower.audiobookplayer.network.NetworkClient
import com.tortugapower.audiobookplayer.repository.AccountRepository
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

class ProfileViewModel(
    private val accountRepository: AccountRepository,
    private val syncTaskRepository: SyncTaskRepository,
    private val statisticsDao: com.tortugapower.audiobookplayer.database.dao.StatisticsDao,
    private val libraryDao: com.tortugapower.audiobookplayer.database.dao.LibraryDao
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
        accountRepository.deleteAccount()
        syncTaskRepository.deleteAllTasks()
        SubscriptionManager.logout()
        SyncStatusManager.updateLastSyncTimestamp(0) // Reset to effectively "Never"
        // AFTER the account row is gone: stop + unload the loaded book if it's now gate-blocked
        // (remote item, no local file). Without this the live session keeps streaming off its
        // still-valid presigned URL — and, because the media service outlives an app swipe, would
        // even resume on the next launch without re-entering any gated load path.
        PlaybackManager.enforceRemoteStreamingGate(com.tortugapower.audiobookplayer.core.CoreContext.appContext)
    }

    fun deleteAllTasks() {
        viewModelScope.launch {
            syncTaskRepository.deleteAllTasks()
        }
    }
}
