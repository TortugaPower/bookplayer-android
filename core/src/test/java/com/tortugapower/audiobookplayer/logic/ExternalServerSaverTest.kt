package com.tortugapower.audiobookplayer.logic

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.ExternalServerEntity
import com.tortugapower.audiobookplayer.database.entities.ExternalServiceType
import com.tortugapower.audiobookplayer.logic.ExternalServerSaver.SignIn
import com.tortugapower.audiobookplayer.repository.ExternalServerRepository
import com.tortugapower.audiobookplayer.repository.TokenCipher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the persistence half of sign-in against a real in-memory Room: fresh rows insert, the same
 * account replaces its row keeping id / library choice / stable id, a moved server updates the
 * re-auth origin row, and only AudiobookShelf reports a stale token to revoke.
 */
@RunWith(RobolectricTestRunner::class)
class ExternalServerSaverTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var repository: ExternalServerRepository

    /** The Keystore isn't available on the JVM; a pass-through cipher keeps the test on the repository's real code path. */
    private object PlainCipher : TokenCipher {
        override fun encrypt(plaintext: String) = plaintext
        override fun decrypt(stored: String) = stored
    }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = ExternalServerRepository(db.externalServerDao(), PlainCipher)
    }

    @After fun tearDown() = db.close()

    private fun signIn(
        type: ExternalServiceType = ExternalServiceType.AUDIOBOOKSHELF,
        url: String = "https://abs.example.com",
        username: String? = "gianni",
        token: String? = "tok-1",
        userId: String? = "u1",
        stableId: String? = "srv-1",
        replacingId: Long? = null,
        headers: Map<String, String>? = null,
    ) = SignIn(type, "Home", url, username, token, headers, stableId, userId, replacingId)

    private fun rows() = runBlocking { repository.allServers.first() }

    @Test fun `a new server inserts and comes back with its id`() = runBlocking {
        val result = ExternalServerSaver.save(repository, signIn(headers = mapOf("CF-Access-Client-Id" to "abc")))
        assertNotEquals(0L, result.server.id)
        assertNull(result.staleTokenToRevoke)
        val stored = rows().single()
        assertEquals(result.server.id, stored.id)
        assertEquals("u1", stored.userId)
        assertEquals(mapOf("CF-Access-Client-Id" to "abc"), stored.customHeaders)
    }

    @Test fun `re-auth of the same account replaces the row and keeps id, library and stable id`() = runBlocking {
        val first = ExternalServerSaver.save(repository, signIn(token = "old")).server
        repository.updateServer(first.copy(selectedLibraryId = "lib-7"))

        val result = ExternalServerSaver.save(repository, signIn(token = "new", stableId = null))

        val stored = rows().single()
        assertEquals(first.id, stored.id)
        assertEquals("new", stored.token)
        assertEquals("lib-7", stored.selectedLibraryId)
        assertEquals("a failed info call must not wipe a captured stable id", "srv-1", stored.stableId)
        assertEquals("old", result.staleTokenToRevoke?.token)
    }

    @Test fun `a different account on the same server forks`() = runBlocking {
        ExternalServerSaver.save(repository, signIn())
        ExternalServerSaver.save(repository, signIn(username = "other", userId = "u2"))
        assertEquals(2, rows().size)
    }

    @Test fun `a moved server updates the origin row instead of orphaning it`() = runBlocking {
        val origin = ExternalServerSaver.save(repository, signIn(url = "https://old.example.com")).server

        ExternalServerSaver.save(repository, signIn(url = "https://moved.example.com", replacingId = origin.id))

        val stored = rows().single()
        assertEquals(origin.id, stored.id)
        assertEquals("https://moved.example.com", stored.url)
    }

    @Test fun `jellyfin never reports a stale token to revoke on re-auth`() = runBlocking {
        ExternalServerSaver.save(repository, signIn(type = ExternalServiceType.JELLYFIN, token = "old"))
        val result = ExternalServerSaver.save(repository, signIn(type = ExternalServiceType.JELLYFIN, token = "new"))
        assertNull(result.staleTokenToRevoke)
        assertEquals("new", rows().single().token)
    }

    @Test fun `an unchanged token is not reported for revocation`() = runBlocking {
        ExternalServerSaver.save(repository, signIn(token = "same"))
        val result = ExternalServerSaver.save(repository, signIn(token = "same"))
        assertNull(result.staleTokenToRevoke)
    }

    @Test fun `blank usernames are stored as null`() = runBlocking {
        val saved = ExternalServerSaver.save(repository, signIn(username = "  ")).server
        assertNull(saved.username)
        assertNull(rows().single().username)
    }

    @Test fun `legacy rows without a user id are still matched by username`() = runBlocking {
        db.externalServerDao().insertServer(
            ExternalServerEntity(name = "Home", type = ExternalServiceType.AUDIOBOOKSHELF, url = "https://abs.example.com", username = "gianni", token = "old")
        )
        ExternalServerSaver.save(repository, signIn(token = "new"))
        val stored = rows().single()
        assertEquals("new", stored.token)
        assertEquals("the account id is captured on the way through", "u1", stored.userId)
    }
}
