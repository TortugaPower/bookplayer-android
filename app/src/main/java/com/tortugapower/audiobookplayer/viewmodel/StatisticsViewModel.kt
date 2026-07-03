package com.tortugapower.audiobookplayer.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tortugapower.audiobookplayer.database.dao.LibraryDao
import com.tortugapower.audiobookplayer.database.dao.StatisticsDao
import com.tortugapower.audiobookplayer.database.entities.PlaybackSessionEntity
import com.tortugapower.audiobookplayer.logic.ListeningStatsCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * State holder for the Statistics and Listening History screens (scoped alongside
 * [ProfileViewModel] in MainScreen, but owning only the session-derived stats).
 */
class StatisticsViewModel(
    private val statisticsDao: StatisticsDao,
    private val libraryDao: LibraryDao
) : ViewModel() {

    // The charts look at most 13 days back (this week plus the previous week for the %
    // comparison), so the query is windowed instead of scanning the whole table. The cutoff is
    // fixed at creation: as time moves forward the window only grows relative to what the
    // mappers filter for, so it stays a superset even if this ViewModel lives across midnights.
    private val sessionWindowStart: Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, -14)
    }.timeInMillis

    private val recentSessionsSharedFlow: SharedFlow<List<PlaybackSessionEntity>> =
        statisticsDao.getSessionsSince(sessionWindowStart)
            .flowOn(Dispatchers.IO)
            .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5000), replay = 1)

    val todayHourlyStats: StateFlow<List<Long>> = recentSessionsSharedFlow
        .map { sessions -> ListeningStatsCalculator.hourlyBuckets(sessions, System.currentTimeMillis()) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), List(24) { 0L })

    val todayChangePercent: StateFlow<Int?> = recentSessionsSharedFlow
        .map { sessions -> ListeningStatsCalculator.dayOverDayChangePercent(sessions, System.currentTimeMillis()) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val weekChangePercent: StateFlow<Int?> = recentSessionsSharedFlow
        .map { sessions -> ListeningStatsCalculator.weekOverWeekChangePercent(sessions, System.currentTimeMillis()) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val weekDailyStats: StateFlow<List<Pair<String, Long>>> = recentSessionsSharedFlow
        .map { sessions -> ListeningStatsCalculator.weekDailyTotals(sessions, System.currentTimeMillis()) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // History is bounded by count rather than the chart window, so older sessions stay visible.
    val playbackHistory: StateFlow<List<PlaybackSessionEntity>> =
        statisticsDao.getRecentSessions(HISTORY_LIMIT)
            .flowOn(Dispatchers.IO)
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

    companion object {
        private const val HISTORY_LIMIT = 500
    }
}

class StatisticsViewModelFactory(
    private val statisticsDao: StatisticsDao,
    private val libraryDao: LibraryDao
) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(StatisticsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return StatisticsViewModel(statisticsDao, libraryDao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
