package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.entities.PlaybackSessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class ListeningStatsCalculatorTest {

    // A fixed "now": some afternoon in the device-default timezone. All session timestamps are
    // derived from it through the local calendar, so the tests are timezone-independent.
    private val now: Long = Calendar.getInstance().apply {
        set(2026, Calendar.JUNE, 15, 15, 30, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** A timestamp [daysAgo] days before today, at [hour]:[minute] local time. */
    private fun at(daysAgo: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            timeInMillis = ListeningStatsCalculator.startOfDay(now)
            add(Calendar.DAY_OF_YEAR, -daysAgo)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
        }.timeInMillis

    private var nextId = 1L
    private fun session(startTime: Long, durationMinutes: Long) = PlaybackSessionEntity(
        id = nextId++,
        bookUuid = "book-1",
        bookTitle = "Book One",
        authorName = null,
        startTime = startTime,
        endTime = startTime + durationMinutes * 60_000,
        duration = durationMinutes * 60_000
    )

    private fun minutes(m: Long) = m * 60_000

    // --- todayListenedTime ---

    @Test
    fun todayListenedTime_sumsOnlyTodaysSessions() {
        val sessions = listOf(
            session(at(0, 9), 30),
            session(at(0, 21), 15),
            session(at(1, 9), 60) // yesterday: excluded
        )
        assertEquals(minutes(45), ListeningStatsCalculator.todayListenedTime(sessions, now))
    }

    @Test
    fun todayListenedTime_emptyIsZero() {
        assertEquals(0L, ListeningStatsCalculator.todayListenedTime(emptyList(), now))
    }

    // --- hourlyBuckets ---

    @Test
    fun hourlyBuckets_groupsByLocalStartHour() {
        val sessions = listOf(
            session(at(0, 9, 5), 30),
            session(at(0, 9, 40), 15),  // same hour: summed
            session(at(0, 22), 10),
            session(at(1, 9), 60)       // yesterday: excluded
        )
        val buckets = ListeningStatsCalculator.hourlyBuckets(sessions, now)

        assertEquals(24, buckets.size)
        assertEquals(minutes(45), buckets[9])
        assertEquals(minutes(10), buckets[22])
        assertEquals(minutes(55), buckets.sum())
    }

    @Test
    fun hourlyBuckets_midnightSessionLandsInBucketZero() {
        val buckets = ListeningStatsCalculator.hourlyBuckets(listOf(session(at(0, 0), 5)), now)
        assertEquals(minutes(5), buckets[0])
    }

    // --- dayOverDayChangePercent ---

    @Test
    fun dayOverDay_positiveChange() {
        val sessions = listOf(
            session(at(1, 9), 60),  // yesterday: 60 min
            session(at(0, 9), 90)   // today: 90 min
        )
        assertEquals(50, ListeningStatsCalculator.dayOverDayChangePercent(sessions, now))
    }

    @Test
    fun dayOverDay_negativeChange() {
        val sessions = listOf(
            session(at(1, 9), 60),
            session(at(0, 9), 30)
        )
        assertEquals(-50, ListeningStatsCalculator.dayOverDayChangePercent(sessions, now))
    }

    @Test
    fun dayOverDay_zeroBaselineIsNull_evenWithListeningToday() {
        val sessions = listOf(session(at(0, 9), 90))
        assertNull(ListeningStatsCalculator.dayOverDayChangePercent(sessions, now))
    }

    @Test
    fun dayOverDay_ignoresOlderDays() {
        val sessions = listOf(
            session(at(2, 9), 60),  // two days ago: not the baseline
            session(at(0, 9), 90)
        )
        assertNull(ListeningStatsCalculator.dayOverDayChangePercent(sessions, now))
    }

    // --- weekOverWeekChangePercent ---

    @Test
    fun weekOverWeek_comparesTheTwoSevenDayWindows() {
        val sessions = listOf(
            session(at(8, 9), 60),   // previous window (7-13 days ago)
            session(at(2, 9), 120)   // current window (0-6 days ago)
        )
        assertEquals(100, ListeningStatsCalculator.weekOverWeekChangePercent(sessions, now))
    }

    @Test
    fun weekOverWeek_zeroBaselineIsNull() {
        val sessions = listOf(session(at(2, 9), 120))
        assertNull(ListeningStatsCalculator.weekOverWeekChangePercent(sessions, now))
    }

    @Test
    fun weekOverWeek_windowBoundaries() {
        val startOfThisWeek = ListeningStatsCalculator.startOfCurrentWeekWindow(now)
        val sessions = listOf(
            // Exactly at the window edge: belongs to the current week...
            session(startOfThisWeek, 30),
            // ...one millisecond earlier: belongs to the previous week.
            session(startOfThisWeek - 1, 60)
        )
        assertEquals(-50, ListeningStatsCalculator.weekOverWeekChangePercent(sessions, now))
    }

    @Test
    fun weekOverWeek_sessionsOlderThanFourteenDaysAreIgnored() {
        val sessions = listOf(
            session(at(14, 9), 60),  // outside both windows
            session(at(2, 9), 120)
        )
        assertNull(ListeningStatsCalculator.weekOverWeekChangePercent(sessions, now))
    }

    // --- weekDailyTotals ---

    @Test
    fun weekDailyTotals_sevenDaysOldestFirstTodayLast() {
        val sessions = listOf(
            session(at(6, 9), 10),  // oldest shown day
            session(at(2, 9), 45),
            session(at(0, 9), 20),  // today
            session(at(7, 9), 99)   // older than the window: excluded
        )
        val daily = ListeningStatsCalculator.weekDailyTotals(sessions, now)

        assertEquals(7, daily.size)
        assertEquals(minutes(10), daily[0].second)  // 6 days ago
        assertEquals(minutes(45), daily[4].second)  // 2 days ago
        assertEquals(minutes(20), daily[6].second)  // today
        assertEquals(minutes(75), daily.sumOf { it.second })
    }

    @Test
    fun weekDailyTotals_labelsMatchEachDaysWeekday() {
        val daily = ListeningStatsCalculator.weekDailyTotals(emptyList(), now)
        val sdf = java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault())

        (6 downTo 0).forEachIndexed { index, daysAgo ->
            val cal = Calendar.getInstance()
            cal.timeInMillis = ListeningStatsCalculator.startOfDay(now)
            cal.add(Calendar.DAY_OF_YEAR, -daysAgo)
            assertEquals(sdf.format(cal.time), daily[index].first)
        }
    }
}
