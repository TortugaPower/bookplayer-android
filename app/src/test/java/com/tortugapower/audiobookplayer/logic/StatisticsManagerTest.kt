package com.tortugapower.audiobookplayer.logic

import android.content.Context
import android.content.ContextWrapper
import com.tortugapower.audiobookplayer.database.dao.StatisticsDao
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.database.entities.PlaybackSessionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StatisticsManagerTest {

    private val testScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private val dummyContext = ContextWrapper(null)

    private val fakeDao = FakeStatisticsDao()
    private var timeCurrent = 1000L

    @Before
    fun setUp() {
        StatisticsManager.scope = testScope
        StatisticsManager.testDao = fakeDao
        StatisticsManager.timeProvider = { timeCurrent }
        fakeDao.sessions.clear()
    }

    @After
    fun tearDown() {
        StatisticsManager.testDao = null
        StatisticsManager.timeProvider = { System.currentTimeMillis() }
    }

    @Test
    fun testSessionLifecycle_startNewSession() = runBlocking {
        val item = LibraryItemEntity(
            uuid = "book-1",
            title = "Book One",
            author = "Author One",
            duration = 100.0,
            currentTime = 0.0,
            percentCompleted = 0.0,
            isFinished = false,
            relativePath = "path/1",
            remoteURL = null,
            artworkURL = null,
            orderRank = 1,
            type = com.tortugapower.audiobookplayer.database.entities.ItemType.BOOK,
            lastPlayDate = null
        )

        // Start playback
        timeCurrent = 10_000L
        StatisticsManager.setPlaybackState(dummyContext, item, isPlaying = true)

        val active = fakeDao.getActiveSession()
        assertNotNull("Should start a new active session", active)
        assertEquals("book-1", active?.bookUuid)
        assertEquals("Book One", active?.bookTitle)
        assertEquals(10_000L, active?.startTime)
        assertNull(active?.endTime)
    }

    @Test
    fun testSessionLifecycle_keepExistingSession() = runBlocking {
        val item = LibraryItemEntity(
            uuid = "book-1",
            title = "Book One",
            author = "Author One",
            duration = 100.0,
            currentTime = 0.0,
            percentCompleted = 0.0,
            isFinished = false,
            relativePath = "path/1",
            remoteURL = null,
            artworkURL = null,
            orderRank = 1,
            type = com.tortugapower.audiobookplayer.database.entities.ItemType.BOOK,
            lastPlayDate = null
        )

        // Start session
        timeCurrent = 10_000L
        StatisticsManager.setPlaybackState(dummyContext, item, isPlaying = true)
        val initialSession = fakeDao.getActiveSession()

        // Send play again for the same book -> should keep existing session
        timeCurrent = 12_000L
        StatisticsManager.setPlaybackState(dummyContext, item, isPlaying = true)
        val currentSession = fakeDao.getActiveSession()

        assertEquals("Should not create a new session", initialSession?.id, currentSession?.id)
        assertEquals(10_000L, currentSession?.startTime)
    }

    @Test
    fun testSessionLifecycle_stopActiveSession_longSession() = runBlocking {
        val item = LibraryItemEntity(
            uuid = "book-1",
            title = "Book One",
            author = "Author One",
            duration = 100.0,
            currentTime = 0.0,
            percentCompleted = 0.0,
            isFinished = false,
            relativePath = "path/1",
            remoteURL = null,
            artworkURL = null,
            orderRank = 1,
            type = com.tortugapower.audiobookplayer.database.entities.ItemType.BOOK,
            lastPlayDate = null
        )

        // Start session
        timeCurrent = 10_000L
        StatisticsManager.setPlaybackState(dummyContext, item, isPlaying = true)

        // Stop session after 5 seconds (5000 ms, > 1000 ms threshold)
        timeCurrent = 15_000L
        StatisticsManager.setPlaybackState(dummyContext, item, isPlaying = false)

        val active = fakeDao.getActiveSession()
        assertNull("Active session should be stopped and finalized", active)

        val finished = fakeDao.sessions.first()
        assertEquals(15_000L, finished.endTime)
        assertEquals(5000L, finished.duration)
    }

    @Test
    fun testSessionLifecycle_stopActiveSession_shortSession() = runBlocking {
        val item = LibraryItemEntity(
            uuid = "book-1",
            title = "Book One",
            author = "Author One",
            duration = 100.0,
            currentTime = 0.0,
            percentCompleted = 0.0,
            isFinished = false,
            relativePath = "path/1",
            remoteURL = null,
            artworkURL = null,
            orderRank = 1,
            type = com.tortugapower.audiobookplayer.database.entities.ItemType.BOOK,
            lastPlayDate = null
        )

        // Start session
        timeCurrent = 10_000L
        StatisticsManager.setPlaybackState(dummyContext, item, isPlaying = true)

        // Stop session after 500ms (< 1000 ms threshold)
        timeCurrent = 10_500L
        StatisticsManager.setPlaybackState(dummyContext, item, isPlaying = false)

        val active = fakeDao.getActiveSession()
        assertNull("Active session should be stopped", active)

        assertTrue("Short session should be deleted from DB", fakeDao.sessions.isEmpty())
    }

    @Test
    fun testSessionLifecycle_heartbeatUpdate() = runBlocking {
        val item = LibraryItemEntity(
            uuid = "book-1",
            title = "Book One",
            author = "Author One",
            duration = 100.0,
            currentTime = 0.0,
            percentCompleted = 0.0,
            isFinished = false,
            relativePath = "path/1",
            remoteURL = null,
            artworkURL = null,
            orderRank = 1,
            type = com.tortugapower.audiobookplayer.database.entities.ItemType.BOOK,
            lastPlayDate = null
        )

        // Start session
        timeCurrent = 10_000L
        StatisticsManager.setPlaybackState(dummyContext, item, isPlaying = true)

        // Trigger updateActiveSessionDuration 10 seconds later
        timeCurrent = 20_000L
        StatisticsManager.updateActiveSessionDuration(dummyContext)

        val session = fakeDao.sessions.first()
        assertEquals(10000L, session.duration) // Updated to 10s (20000 - 10000)
    }

    private class FakeStatisticsDao : StatisticsDao {
        val sessions = mutableListOf<PlaybackSessionEntity>()
        private var nextId = 1L

        override suspend fun insertSession(session: PlaybackSessionEntity): Long {
            val id = nextId++
            val saved = session.copy(id = id)
            sessions.add(saved)
            return id
        }

        override suspend fun updateSession(session: PlaybackSessionEntity) {
            val idx = sessions.indexOfFirst { it.id == session.id }
            if (idx != -1) {
                sessions[idx] = session
            }
        }

        override suspend fun deleteSession(session: PlaybackSessionEntity) {
            sessions.removeIf { it.id == session.id }
        }

        override suspend fun getActiveSession(): PlaybackSessionEntity? {
            return sessions.find { it.endTime == null }
        }

        override fun getTotalPlaytimeFlow(): Flow<Long?> = TODO()
        override fun getUniqueBooksFlow(): Flow<Int> = TODO()
        override fun getUniqueAuthorsFlow(): Flow<Int> = TODO()
        override fun getFavoriteBookArtworkFlow(): Flow<String?> = TODO()
        override fun getFavoriteBookTitleFlow(): Flow<String?> = TODO()
        override fun getAllSessionsFlow(): Flow<List<PlaybackSessionEntity>> = TODO()
        override fun getDaysListenedFlow(): Flow<Int> = TODO()
    }
}
