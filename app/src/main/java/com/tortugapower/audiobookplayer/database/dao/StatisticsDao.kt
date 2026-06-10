package com.tortugapower.audiobookplayer.database.dao

import androidx.room.*
import com.tortugapower.audiobookplayer.database.entities.PlaybackSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StatisticsDao {
    @Insert
    suspend fun insertSession(session: PlaybackSessionEntity): Long

    @Update
    suspend fun updateSession(session: PlaybackSessionEntity)

    @Query("SELECT * FROM playback_sessions WHERE endTime IS NULL ORDER BY startTime DESC LIMIT 1")
    suspend fun getActiveSession(): PlaybackSessionEntity?

    @Query("SELECT SUM(duration) FROM playback_sessions")
    fun getTotalPlaytimeFlow(): Flow<Long?>

    @Query("SELECT COUNT(DISTINCT bookUuid) FROM playback_sessions")
    fun getUniqueBooksFlow(): Flow<Int>

    @Query("SELECT COUNT(DISTINCT authorName) FROM playback_sessions WHERE authorName IS NOT NULL")
    fun getUniqueAuthorsFlow(): Flow<Int>

    @Query("SELECT bookTitle FROM playback_sessions GROUP BY bookUuid ORDER BY SUM(duration) DESC LIMIT 1")
    fun getFavoriteBookFlow(): Flow<String?>

    @Query("SELECT library_items.artworkURL FROM playback_sessions JOIN library_items ON playback_sessions.bookUuid = library_items.uuid GROUP BY playback_sessions.bookUuid ORDER BY SUM(playback_sessions.duration) DESC LIMIT 1")
    fun getFavoriteBookArtworkFlow(): Flow<String?>

    @Query("SELECT authorName FROM playback_sessions WHERE authorName IS NOT NULL GROUP BY authorName ORDER BY SUM(duration) DESC LIMIT 1")
    fun getFavoriteAuthorFlow(): Flow<String?>

    @Query("SELECT * FROM playback_sessions ORDER BY startTime DESC")
    fun getAllSessionsFlow(): Flow<List<PlaybackSessionEntity>>

    @Query("SELECT COUNT(DISTINCT strftime('%Y-%m-%d', startTime / 1000, 'unixepoch')) FROM playback_sessions")
    fun getDaysListenedFlow(): Flow<Int>

    @Query("SELECT (startTime / 3600000) % 24 as hour, SUM(duration) as totalDuration FROM playback_sessions GROUP BY hour")
    fun getHourlyDistributionFlow(): Flow<List<HourlyStat>>
}

data class HourlyStat(
    val hour: Int,
    val totalDuration: Long
)
