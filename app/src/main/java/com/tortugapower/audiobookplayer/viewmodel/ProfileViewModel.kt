package com.tortugapower.audiobookplayer.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tortugapower.audiobookplayer.database.entities.AccountEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import com.tortugapower.audiobookplayer.database.entities.PlaybackSessionEntity
import com.tortugapower.audiobookplayer.logic.SubscriptionManager
import com.tortugapower.audiobookplayer.logic.SyncStatusManager
import com.tortugapower.audiobookplayer.network.NetworkClient
import com.tortugapower.audiobookplayer.repository.AccountRepository
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import java.util.Calendar
import java.util.Locale
import java.text.SimpleDateFormat

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

    val isSubscribed: StateFlow<Boolean> = account.map { 
        it != null && (it.tier == com.tortugapower.audiobookplayer.database.entities.AccountTier.PRO || 
                      it.tier == com.tortugapower.audiobookplayer.database.entities.AccountTier.LITE)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val totalPlaytime: StateFlow<Long> = statisticsDao.getTotalPlaytimeFlow()
        .map { it ?: 0L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val completedBooks: StateFlow<Int> = libraryDao.getCompletedBooksCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val daysListened: StateFlow<Int> = statisticsDao.getDaysListenedFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val mostListenedBookArtwork: StateFlow<String?> = statisticsDao.getMostListenedBookArtworkFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val allSessionsSharedFlow: SharedFlow<List<PlaybackSessionEntity>> = statisticsDao.getAllSessionsFlow()
        .flowOn(Dispatchers.IO)
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5000), replay = 1)

    private fun startOfTodayMillis(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    val todayListenedTime: StateFlow<Long> = allSessionsSharedFlow
        .map { sessions ->
            val startOfToday = startOfTodayMillis()
            sessions.filter { it.startTime >= startOfToday }.sumOf { it.duration }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val todayHourlyStats: StateFlow<List<Long>> = allSessionsSharedFlow
        .map { sessions ->
            val hourly = MutableList(24) { 0L }
            val startOfToday = startOfTodayMillis()
            val cal = Calendar.getInstance()

            sessions.filter { it.startTime >= startOfToday }.forEach { session ->
                cal.timeInMillis = session.startTime
                val hour = cal.get(Calendar.HOUR_OF_DAY)
                if (hour in 0..23) {
                    hourly[hour] += session.duration
                }
            }
            hourly
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), List(24) { 0L })

    val todayChangePercent: StateFlow<Int?> = allSessionsSharedFlow
        .map { sessions ->
            val startOfToday = startOfTodayMillis()
            val startOfYesterday = startOfToday - 24 * 60 * 60 * 1000

            val todayTime = sessions.filter { it.startTime >= startOfToday }.sumOf { it.duration }
            val yesterdayTime = sessions.filter { it.startTime in startOfYesterday until startOfToday }.sumOf { it.duration }

            if (yesterdayTime == 0L) {
                if (todayTime > 0L) 100 else null
            } else {
                (((todayTime - yesterdayTime).toDouble() / yesterdayTime.toDouble()) * 100).toInt()
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val weekChangePercent: StateFlow<Int?> = allSessionsSharedFlow
        .map { sessions ->
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            cal.add(Calendar.DAY_OF_YEAR, -6)
            val startOfThisWeek = cal.timeInMillis
            val startOfPrevWeek = startOfThisWeek - 7L * 24 * 60 * 60 * 1000

            val thisWeekTime = sessions.filter { it.startTime >= startOfThisWeek }.sumOf { it.duration }
            val prevWeekTime = sessions.filter { it.startTime in startOfPrevWeek until startOfThisWeek }.sumOf { it.duration }

            if (prevWeekTime == 0L) {
                if (thisWeekTime > 0L) 100 else null
            } else {
                (((thisWeekTime - prevWeekTime).toDouble() / prevWeekTime.toDouble()) * 100).toInt()
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val weekDailyStats: StateFlow<List<Pair<String, Long>>> = allSessionsSharedFlow
        .map { sessions ->
            val daily = mutableListOf<Pair<String, Long>>()
            val sdf = SimpleDateFormat("EEE", Locale.getDefault())
            
            val days = (0..6).map { offset ->
                val dayCal = Calendar.getInstance()
                dayCal.set(Calendar.HOUR_OF_DAY, 0)
                dayCal.set(Calendar.MINUTE, 0)
                dayCal.set(Calendar.SECOND, 0)
                dayCal.set(Calendar.MILLISECOND, 0)
                dayCal.add(Calendar.DAY_OF_YEAR, -(6 - offset))
                dayCal
            }

            days.forEach { dayCal ->
                val startOfDay = dayCal.timeInMillis
                dayCal.add(Calendar.DAY_OF_YEAR, 1)
                val endOfDay = dayCal.timeInMillis
                dayCal.add(Calendar.DAY_OF_YEAR, -1)

                val daySessions = sessions.filter { it.startTime in startOfDay until endOfDay }
                val totalDuration = daySessions.sumOf { it.duration }
                val label = sdf.format(dayCal.time)
                daily.add(Pair(label, totalDuration))
            }
            daily
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val playbackHistory: StateFlow<List<PlaybackSessionEntity>> = allSessionsSharedFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun playBook(context: android.content.Context, bookUuid: String, onNotFound: () -> Unit = {}) {
        viewModelScope.launch {
            val item = libraryDao.getItemById(bookUuid)
            if (item != null) {
                com.tortugapower.audiobookplayer.logic.PlaybackManager.playItem(context, item)
            } else {
                onNotFound()
            }
        }
    }

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
    }

    fun deleteAllTasks() {
        viewModelScope.launch {
            syncTaskRepository.deleteAllTasks()
        }
    }
}
