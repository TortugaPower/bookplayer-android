package com.tortugapower.audiobookplayer.logic

import android.content.ContextWrapper
import com.tortugapower.audiobookplayer.database.dao.StatisticsDao
import com.tortugapower.audiobookplayer.database.entities.ItemType
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

    private lateinit var originalScope: CoroutineScope

    @Before
    fun setUp() {
        originalScope = StatisticsManager.scope
        StatisticsManager.scope = testScope
        StatisticsManager.testDao = fakeDao
        StatisticsManager.timeProvider = { timeCurrent }
        fakeDao.sessions.clear()
    }

    @After
    fun tearDown() {
        StatisticsManager.scope = originalScope
        StatisticsManager.testDao = null
        StatisticsManager.timeProvider = { System.currentTimeMillis() }
    }

    private fun makeItem(uuid: String, title: String) = LibraryItemEntity(
        uuid = uuid,
        title = title,
        author = "Author One",
        duration = 100.0,
        currentTime = 0.0,
        percentCompleted = 0.0,
        isFinished = false,
        relativePath = "path/$uuid",
        remoteURL = null,
        artworkURL = null,
        orderRank = 1,
        type = ItemType.BOOK,
        lastPlayDate = null
    )

    @Test
    fun testSessionLifecycle_startNewSession() = runBlocking {
        val item = makeItem("book-1", "Book One")

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
        val item = makeItem("book-1", "Book One")

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
        val item = makeItem("book-1", "Book One")

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
        val item = makeItem("book-1", "Book One")

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
    fun testSessionLifecycle_switchingBooks_stopsPreviousAndStartsNew() = runBlocking {
        val bookOne = makeItem("book-1", "Book One")
        val bookTwo = makeItem("book-2", "Book Two")

        timeCurrent = 10_000L
        StatisticsManager.setPlaybackState(dummyContext, bookOne, isPlaying = true)

        // Switching to another book must finalize book-1's session and start book-2's
        timeCurrent = 15_000L
        StatisticsManager.setPlaybackState(dummyContext, bookTwo, isPlaying = true)

        assertEquals(2, fakeDao.sessions.size)
        val finished = fakeDao.sessions.first { it.bookUuid == "book-1" }
        assertEquals(15_000L, finished.endTime)
        assertEquals(5000L, finished.duration)

        val active = fakeDao.getActiveSession()
        assertEquals("book-2", active?.bookUuid)
        assertEquals(15_000L, active?.startTime)
    }

    @Test
    fun testSessionLifecycle_heartbeatUpdate() = runBlocking {
        val item = makeItem("book-1", "Book One")

        // Start session
        timeCurrent = 10_000L
        StatisticsManager.setPlaybackState(dummyContext, item, isPlaying = true)

        // Trigger updateActiveSessionDuration 10 seconds later
        timeCurrent = 20_000L
        StatisticsManager.updateActiveSessionDuration(dummyContext)

        val session = fakeDao.sessions.first()
        assertEquals(10000L, session.duration) // Updated to 10s (20000 - 10000)
    }

    @Test
    fun testHeartbeat_noActiveSession_isNoop() = runBlocking {
        timeCurrent = 10_000L
        StatisticsManager.updateActiveSessionDuration(dummyContext)

        assertTrue(fakeDao.sessions.isEmpty())
    }

    // Process killed mid-playback: the session is left open (endTime == null) and the next
    // playback event arrives much later. The offline gap must NOT count as listening time.

    @Test
    fun testOrphanedSession_differentBook_finalizedAtLastHeartbeat() = runBlocking {
        val bookOne = makeItem("book-1", "Book One")
        val bookTwo = makeItem("book-2", "Book Two")

        timeCurrent = 10_000L
        StatisticsManager.setPlaybackState(dummyContext, bookOne, isPlaying = true)
        timeCurrent = 20_000L
        StatisticsManager.updateActiveSessionDuration(dummyContext) // last heartbeat: 10s
        // ... process dies, no stop event; user comes back much later and plays another book

        timeCurrent = 1_000_000L
        StatisticsManager.setPlaybackState(dummyContext, bookTwo, isPlaying = true)

        val orphan = fakeDao.sessions.first { it.bookUuid == "book-1" }
        assertEquals("Orphan must close at its last heartbeat, not at wall-clock now",
            20_000L, orphan.endTime)
        assertEquals(10_000L, orphan.duration)

        val active = fakeDao.getActiveSession()
        assertEquals("book-2", active?.bookUuid)
        assertEquals(1_000_000L, active?.startTime)
    }

    @Test
    fun testOrphanedSession_sameBook_notResumed() = runBlocking {
        val item = makeItem("book-1", "Book One")

        timeCurrent = 10_000L
        StatisticsManager.setPlaybackState(dummyContext, item, isPlaying = true)
        timeCurrent = 20_000L
        StatisticsManager.updateActiveSessionDuration(dummyContext) // last heartbeat: 10s
        // ... process dies; user resumes the SAME book much later

        timeCurrent = 1_000_000L
        StatisticsManager.setPlaybackState(dummyContext, item, isPlaying = true)

        assertEquals(2, fakeDao.sessions.size)
        val orphan = fakeDao.sessions.first { it.endTime != null }
        assertEquals(20_000L, orphan.endTime)
        assertEquals(10_000L, orphan.duration)

        val active = fakeDao.getActiveSession()
        assertEquals("A fresh session starts instead of resuming the stale one",
            1_000_000L, active?.startTime)
    }

    @Test
    fun testOrphanedSession_withoutHeartbeat_isDeleted() = runBlocking {
        val bookOne = makeItem("book-1", "Book One")
        val bookTwo = makeItem("book-2", "Book Two")

        timeCurrent = 10_000L
        StatisticsManager.setPlaybackState(dummyContext, bookOne, isPlaying = true)
        // ... process dies before the first heartbeat ever persists a duration

        timeCurrent = 1_000_000L
        StatisticsManager.setPlaybackState(dummyContext, bookTwo, isPlaying = true)

        assertTrue("Orphan with no recorded listening time is dropped",
            fakeDao.sessions.none { it.bookUuid == "book-1" })
        assertEquals("book-2", fakeDao.getActiveSession()?.bookUuid)
    }

    @Test
    fun testOrphanedSession_heartbeatDoesNotInflateIt() = runBlocking {
        val item = makeItem("book-1", "Book One")

        timeCurrent = 10_000L
        StatisticsManager.setPlaybackState(dummyContext, item, isPlaying = true)
        timeCurrent = 20_000L
        StatisticsManager.updateActiveSessionDuration(dummyContext) // last heartbeat: 10s
        // ... process dies; a heartbeat fires much later (e.g. stale WorkManager/timer state)

        timeCurrent = 1_000_000L
        StatisticsManager.updateActiveSessionDuration(dummyContext)

        val session = fakeDao.sessions.first()
        assertEquals("Stale session is closed, not extended", 20_000L, session.endTime)
        assertEquals(10_000L, session.duration)
        assertNull(fakeDao.getActiveSession())
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

        // Mirrors the production query: most recent open session wins.
        override suspend fun getActiveSession(): PlaybackSessionEntity? {
            return sessions.filter { it.endTime == null }.maxByOrNull { it.startTime }
        }

        override fun getTotalPlaytimeFlow(): Flow<Long?> = TODO()
        override fun getMostListenedBookArtworkFlow(): Flow<String?> = TODO()
        override fun getAllSessionsFlow(): Flow<List<PlaybackSessionEntity>> = TODO()
        override fun getDaysListenedFlow(): Flow<Int> = TODO()
    }
}
