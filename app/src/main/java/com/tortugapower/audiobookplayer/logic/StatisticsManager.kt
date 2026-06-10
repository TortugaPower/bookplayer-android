package com.tortugapower.audiobookplayer.logic

import android.content.Context
import android.util.Log
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.database.entities.PlaybackSessionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object StatisticsManager {
    private const val TAG = "StatisticsManager"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()

    fun setPlaybackState(context: Context, item: LibraryItemEntity?, isPlaying: Boolean) {
        scope.launch {
            mutex.withLock {
                val dao = AppDatabase.getDatabase(context).statisticsDao()
                val activeSession = dao.getActiveSession()

                if (isPlaying && item != null) {
                    // If we already have an active session for this book, just keep it
                    if (activeSession != null && activeSession.bookUuid == item.uuid) {
                        Log.d(TAG, "Keep existing session ${activeSession.id} for ${item.title}")
                        return@withLock
                    }

                    // Otherwise, stop any existing session and start a new one
                    stopActiveSessionInternal(dao)

                    val session = PlaybackSessionEntity(
                        bookUuid = item.uuid,
                        bookTitle = item.title,
                        authorName = item.author,
                        startTime = System.currentTimeMillis()
                    )
                    val id = dao.insertSession(session)
                    Log.d(TAG, "🚀 Started new session: $id for ${item.title}")
                } else {
                    // Stopped or item is null
                    stopActiveSessionInternal(dao)
                }
            }
        }
    }

    private suspend fun stopActiveSessionInternal(dao: com.tortugapower.audiobookplayer.database.dao.StatisticsDao) {
        val activeSession = dao.getActiveSession()
        if (activeSession != null) {
            val now = System.currentTimeMillis()
            val duration = now - activeSession.startTime
            
            // Only save sessions longer than 1 second to avoid noise from rapid toggling
            if (duration > 1000) {
                activeSession.endTime = now
                activeSession.duration = duration
                dao.updateSession(activeSession)
                Log.d(TAG, "⏹️ Stopped session: ${activeSession.id}. Duration: ${duration}ms")
            } else {
                // If it was too short, just delete it or mark as ended without duration
                // For simplicity, let's just mark as ended so it's not "active" anymore
                activeSession.endTime = now
                activeSession.duration = Math.max(0, duration)
                dao.updateSession(activeSession)
                Log.d(TAG, "⏹️ Stopped very short session: ${activeSession.id}. Duration: ${activeSession.duration}ms")
            }
        }
    }

    /**
     * Should be called periodically during playback to ensure duration is updated 
     * even if the app crashes or is killed.
     */
    fun updateActiveSessionDuration(context: Context) {
        scope.launch {
            mutex.withLock {
                val dao = AppDatabase.getDatabase(context).statisticsDao()
                val activeSession = dao.getActiveSession()
                
                if (activeSession != null) {
                    val now = System.currentTimeMillis()
                    val newDuration = now - activeSession.startTime
                    if (newDuration > activeSession.duration) {
                        activeSession.duration = newDuration
                        dao.updateSession(activeSession)
                        Log.d(TAG, "💓 Heartbeat: Updated session ${activeSession.id} duration to: ${newDuration}ms")
                    }
                }
            }
        }
    }
}
