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

    @Delete
    suspend fun deleteSession(session: PlaybackSessionEntity)

    @Query("SELECT * FROM playback_sessions WHERE endTime IS NULL ORDER BY startTime DESC LIMIT 1")
    suspend fun getActiveSession(): PlaybackSessionEntity?

    @Query("SELECT SUM(duration) FROM playback_sessions")
    fun getTotalPlaytimeFlow(): Flow<Long?>

    @Query("SELECT library_items.artworkURL FROM playback_sessions JOIN library_items ON playback_sessions.bookUuid = library_items.uuid GROUP BY playback_sessions.bookUuid ORDER BY SUM(playback_sessions.duration) DESC LIMIT 1")
    fun getMostListenedBookArtworkFlow(): Flow<String?>

    @Query("SELECT * FROM playback_sessions ORDER BY startTime DESC")
    fun getAllSessionsFlow(): Flow<List<PlaybackSessionEntity>>

    @Query("SELECT COUNT(DISTINCT strftime('%Y-%m-%d', startTime / 1000, 'unixepoch', 'localtime')) FROM playback_sessions")
    fun getDaysListenedFlow(): Flow<Int>
}
