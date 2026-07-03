package com.tortugapower.audiobookplayer.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tortugapower.audiobookplayer.database.dao.LibraryDao
import com.tortugapower.audiobookplayer.database.dao.StatisticsDao
import com.tortugapower.audiobookplayer.database.entities.PlaybackSessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

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

    private fun startOfTodayMillis(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    val todayHourlyStats: StateFlow<List<Long>> = recentSessionsSharedFlow
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

    val todayChangePercent: StateFlow<Int?> = recentSessionsSharedFlow
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

    val weekChangePercent: StateFlow<Int?> = recentSessionsSharedFlow
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

    val weekDailyStats: StateFlow<List<Pair<String, Long>>> = recentSessionsSharedFlow
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
