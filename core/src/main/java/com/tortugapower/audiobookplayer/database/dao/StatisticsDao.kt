package com.tortugapower.audiobookplayer.database.dao

import androidx.room.*
import com.tortugapower.audiobookplayer.database.entities.PlaybackSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StatisticsDao {
    @Insert
    suspend fun insertSession(session: PlaybackSessionEntity): Long

    @Query("SELECT COUNT(*) FROM library_items WHERE uuid = :uuid")
    suspend fun libraryItemExists(uuid: String): Int

    /**
     * Insert [session] only if its book is still in the library, returning the new id or null.
     * `playback_sessions.bookUuid` is a FOREIGN KEY to `library_items`; a session for a book that was
     * deleted or replaced by a sync pull while it was playing would otherwise fail the insert
     * (Sentry ANDROID-BOOKPLAYER-19). One transaction, so the check cannot race the delete.
     */
    @Transaction
    suspend fun startSession(session: PlaybackSessionEntity): Long? {
        if (libraryItemExists(session.bookUuid) == 0) return null
        return insertSession(session)
    }

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

    @Query("SELECT * FROM playback_sessions WHERE startTime >= :cutoff ORDER BY startTime DESC")
    fun getSessionsSince(cutoff: Long): Flow<List<PlaybackSessionEntity>>

    @Query("SELECT * FROM playback_sessions ORDER BY startTime DESC LIMIT :limit")
    fun getRecentSessions(limit: Int): Flow<List<PlaybackSessionEntity>>

    @Query("SELECT COUNT(DISTINCT strftime('%Y-%m-%d', startTime / 1000, 'unixepoch', 'localtime')) FROM playback_sessions")
    fun getDaysListenedFlow(): Flow<Int>
}
