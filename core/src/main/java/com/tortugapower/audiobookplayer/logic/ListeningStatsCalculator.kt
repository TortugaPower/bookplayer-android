package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.entities.PlaybackSessionEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Pure aggregation math behind the Profile overview card and the Statistics charts.
 * "Now" is always passed in explicitly so the bucketing and window edges are unit-testable;
 * days are bucketed by the device's local calendar.
 */
object ListeningStatsCalculator {

    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun startOfDay(timeMs: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timeMs
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** Total duration of sessions started today. */
    fun todayListenedTime(sessions: List<PlaybackSessionEntity>, now: Long): Long {
        val startOfToday = startOfDay(now)
        return sessions.filter { it.startTime >= startOfToday }.sumOf { it.duration }
    }

    /** 24 buckets of listening time for the day containing [now], keyed by local start hour. */
    fun hourlyBuckets(sessions: List<PlaybackSessionEntity>, now: Long): List<Long> {
        val hourly = MutableList(24) { 0L }
        val startOfToday = startOfDay(now)
        val cal = Calendar.getInstance()

        sessions.filter { it.startTime >= startOfToday }.forEach { session ->
            cal.timeInMillis = session.startTime
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            if (hour in 0..23) {
                hourly[hour] += session.duration
            }
        }
        return hourly
    }

    /**
     * Percentage change of today's listening vs yesterday's, or null when yesterday had none —
     * a percentage against a zero baseline is undefined, so no label is shown.
     */
    fun dayOverDayChangePercent(sessions: List<PlaybackSessionEntity>, now: Long): Int? {
        val startOfToday = startOfDay(now)
        val startOfYesterday = startOfToday - DAY_MS

        val todayTime = sessions.filter { it.startTime >= startOfToday }.sumOf { it.duration }
        val yesterdayTime = sessions.filter { it.startTime in startOfYesterday until startOfToday }.sumOf { it.duration }

        return if (yesterdayTime == 0L) {
            null
        } else {
            (((todayTime - yesterdayTime).toDouble() / yesterdayTime.toDouble()) * 100).toInt()
        }
    }

    /**
     * Percentage change of the current 7-day window (today plus the 6 days before) vs the 7 days
     * preceding it, or null when the previous window had no listening.
     */
    fun weekOverWeekChangePercent(sessions: List<PlaybackSessionEntity>, now: Long): Int? {
        val startOfThisWeek = startOfCurrentWeekWindow(now)
        val startOfPrevWeek = startOfThisWeek - 7 * DAY_MS

        val thisWeekTime = sessions.filter { it.startTime >= startOfThisWeek }.sumOf { it.duration }
        val prevWeekTime = sessions.filter { it.startTime in startOfPrevWeek until startOfThisWeek }.sumOf { it.duration }

        return if (prevWeekTime == 0L) {
            null
        } else {
            (((thisWeekTime - prevWeekTime).toDouble() / prevWeekTime.toDouble()) * 100).toInt()
        }
    }

    /**
     * Listening totals for the last 7 days (oldest first, today last), each labelled with its
     * localized weekday abbreviation.
     */
    fun weekDailyTotals(sessions: List<PlaybackSessionEntity>, now: Long): List<Pair<String, Long>> {
        val daily = mutableListOf<Pair<String, Long>>()
        val sdf = SimpleDateFormat("EEE", Locale.getDefault())

        (6 downTo 0).forEach { daysAgo ->
            val dayCal = Calendar.getInstance()
            dayCal.timeInMillis = startOfDay(now)
            dayCal.add(Calendar.DAY_OF_YEAR, -daysAgo)
            val startOfDay = dayCal.timeInMillis
            dayCal.add(Calendar.DAY_OF_YEAR, 1)
            val endOfDay = dayCal.timeInMillis
            dayCal.add(Calendar.DAY_OF_YEAR, -1)

            val totalDuration = sessions
                .filter { it.startTime in startOfDay until endOfDay }
                .sumOf { it.duration }
            daily.add(Pair(sdf.format(dayCal.time), totalDuration))
        }
        return daily
    }

    /** Local midnight 6 days before today — the first day shown on the Week chart. */
    fun startOfCurrentWeekWindow(now: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = startOfDay(now)
        cal.add(Calendar.DAY_OF_YEAR, -6)
        return cal.timeInMillis
    }
}
