package com.tortugapower.audiobookplayer.logic

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.dao.StatisticsDao
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.database.entities.PlaybackSessionEntity
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object StatisticsManager {
    private const val TAG = "StatisticsManager"

    // Sessions shorter than this are dropped as noise from rapid play/pause toggling.
    private const val MIN_SESSION_DURATION_MS = 1_000L

    // An active session whose wall-clock age exceeds its heartbeat-persisted duration by more
    // than this is an orphan from a killed process (heartbeats run every
    // PlaybackTickPolicy.PERSIST_INTERVAL_MS = 10s, so a live session never drifts this far).
    private const val STALE_SESSION_THRESHOLD_MS = 30_000L

    /**
     * Listening statistics are bookkeeping: a failed write (a constraint the guards below didn't foresee,
     * a full disk) must never take playback — or the process — down with it. Uncaught exceptions in the
     * statistics coroutines are logged here instead of reaching the thread's uncaught handler.
     */
    @VisibleForTesting
    internal val exceptionHandler = CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "Statistics update failed; playback is unaffected", e)
    }

    // Single-parallelism dispatcher so events are processed strictly in submission order —
    // a fast pause→play toggle must never run its play half before its pause half.
    @OptIn(ExperimentalCoroutinesApi::class)
    @VisibleForTesting
    internal var scope = CoroutineScope(Dispatchers.IO.limitedParallelism(1) + SupervisorJob() + exceptionHandler)

    @VisibleForTesting
    internal var testDao: StatisticsDao? = null

    @VisibleForTesting
    internal var timeProvider: () -> Long = { System.currentTimeMillis() }

    fun setPlaybackState(context: Context, item: LibraryItemEntity?, isPlaying: Boolean) {
        scope.launch {
            val dao = testDao ?: AppDatabase.getDatabase(context).statisticsDao()
            val activeSession = reconcileActiveSession(dao)

            if (isPlaying && item != null) {
                // If we already have an active session for this book, just keep it
                if (activeSession != null && activeSession.bookUuid == item.uuid) {
                    Log.d(TAG, "Keep existing session ${activeSession.id} for ${item.title}")
                    return@launch
                }

                // Otherwise, stop any existing session and start a new one
                stopSession(dao, activeSession)

                val session = PlaybackSessionEntity(
                    bookUuid = item.uuid,
                    bookTitle = item.title,
                    authorName = item.author,
                    startTime = timeProvider()
                )
                val id = dao.startSession(session)
                if (id == null) {
                    Log.w(TAG, "Book ${item.uuid} is no longer in the library; not recording a session")
                    return@launch
                }
                Log.d(TAG, "🚀 Started new session: $id for ${item.title}")
            } else {
                // Stopped or item is null
                stopSession(dao, activeSession)
            }
        }
    }

    /**
     * Should be called periodically during playback so the persisted duration stays current
     * even if the app crashes or is killed; [reconcileActiveSession] relies on it to close
     * orphaned sessions at the last heartbeat instead of counting the offline gap.
     */
    fun updateActiveSessionDuration(context: Context) {
        scope.launch {
            val dao = testDao ?: AppDatabase.getDatabase(context).statisticsDao()
            val activeSession = reconcileActiveSession(dao) ?: return@launch

            val newDuration = timeProvider() - activeSession.startTime
            if (newDuration > activeSession.duration) {
                activeSession.duration = newDuration
                dao.updateSession(activeSession)
                Log.d(TAG, "💓 Heartbeat: Updated session ${activeSession.id} duration to: ${newDuration}ms")
            }
        }
    }

    /**
     * Returns the live active session, or null after closing an orphaned one. A session is an
     * orphan when the process died mid-playback: no stop event fired, so its wall-clock age
     * keeps growing past the heartbeat-persisted duration. Orphans are finalized at the last
     * heartbeat (or deleted if none ever ran) so the dead time never counts as listening.
     */
    private suspend fun reconcileActiveSession(dao: StatisticsDao): PlaybackSessionEntity? {
        val activeSession = dao.getActiveSession() ?: return null

        val wallClockAge = timeProvider() - activeSession.startTime
        if (wallClockAge - activeSession.duration <= STALE_SESSION_THRESHOLD_MS) {
            return activeSession
        }

        if (activeSession.duration >= MIN_SESSION_DURATION_MS) {
            activeSession.endTime = activeSession.startTime + activeSession.duration
            dao.updateSession(activeSession)
            Log.d(TAG, "🧹 Closed orphaned session ${activeSession.id} at last heartbeat: ${activeSession.duration}ms")
        } else {
            dao.deleteSession(activeSession)
            Log.d(TAG, "🗑️ Deleted orphaned session ${activeSession.id} with no heartbeat")
        }
        return null
    }

    private suspend fun stopSession(dao: StatisticsDao, activeSession: PlaybackSessionEntity?) {
        if (activeSession == null) return

        val now = timeProvider()
        val duration = now - activeSession.startTime

        // Only save sessions longer than 1 second to avoid noise from rapid toggling
        if (duration >= MIN_SESSION_DURATION_MS) {
            activeSession.endTime = now
            activeSession.duration = duration
            dao.updateSession(activeSession)
            Log.d(TAG, "⏹️ Stopped session: ${activeSession.id}. Duration: ${duration}ms")
        } else {
            dao.deleteSession(activeSession)
            Log.d(TAG, "🗑️ Deleted short session: ${activeSession.id}. Duration: ${duration}ms")
        }
    }
}
