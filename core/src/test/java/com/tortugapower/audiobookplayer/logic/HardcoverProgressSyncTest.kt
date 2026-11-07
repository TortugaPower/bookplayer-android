package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.network.HardcoverOAuthException
import com.tortugapower.audiobookplayer.network.HardcoverReadProgress
import com.tortugapower.audiobookplayer.network.HardcoverService.HardcoverRequestException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** When a book's Hardcover progress is queued (its whole percentage changed), and what's worth retrying in its run. */
class HardcoverProgressSyncTest {

    private fun send(uuid: String = "u1", result: () -> HardcoverProgressResult) = runBlocking {
        HardcoverProgressSync.send(uuid, 40.0) { _, _ -> result() }
    }

    @Test fun `a book's progress is new when its whole percentage changes (iOS)`() {
        val changes = HardcoverProgressChanges()
        assertTrue(changes.isNew("u1", 40.2))
        assertFalse("same whole percentage", changes.isNew("u1", 40.9))
        assertTrue(changes.isNew("u1", 41.0))
        assertTrue("another book has its own", changes.isNew("u2", 41.0))
        assertTrue("going back counts too", changes.isNew("u1", 39.5))
    }

    @Test fun `a book back at the start, or an unlink, makes its next percentage new`() {
        val changes = HardcoverProgressChanges()
        changes.isNew("u1", 40.0)
        changes.isNew("u2", 50.0)

        changes.forget("u1")
        assertTrue(changes.isNew("u1", 40.0))

        changes.clear()
        assertTrue(changes.isNew("u2", 50.0))
    }

    @Test fun `what Hardcover may get over is retried`() {
        for (status in listOf(0, 408, 429, 500, 503)) {
            assertThrows("HTTP $status", HardcoverRequestException::class.java) {
                send { throw HardcoverRequestException(status, "HTTP $status") }
            }
        }
        // Offline: the request's own transport error
        assertThrows(IOException::class.java) { send { throw IOException("offline") } }
    }

    @Test fun `a refusal, a skip or a send is done with`() {
        // A refused key: `me` (or the progress request) answers 401, and nothing would ever change that
        send { throw HardcoverRequestException(401, "HTTP 401") }
        send { throw HardcoverRequestException(403, "HTTP 403") }
        send { HardcoverProgressResult.NoUserId }
        send { throw HardcoverRequestException(400, "HTTP 400") }
        send { HardcoverProgressResult.Refused("nope") }
        send { HardcoverProgressResult.NoReadInProgress }
        send { HardcoverProgressResult.Skipped(HardcoverReadProgress.Skip.NoLength) }
        send { HardcoverProgressResult.Sent }
        // An answer that doesn't parse won't parse next time either
        send { throw com.google.gson.JsonSyntaxException("Expected a string") }
        send { throw IllegalStateException("not a string") }
    }

    @Test fun `a refresh the token endpoint may answer later is retried, a refused one is dropped`() {
        val busy = listOf(
            HardcoverOAuthException.Http(503), HardcoverOAuthException.Http(429),
            HardcoverOAuthException.Server("temporarily_unavailable", null),
        )
        for (e in busy) assertThrows(e.message, HardcoverOAuthException::class.java) { send { throw e } }

        send { throw HardcoverOAuthException.Server("invalid_client", null) }
        send { throw HardcoverOAuthException.Server("invalid_request", null) }
        send { throw HardcoverOAuthException.Http(400) }
        send { throw HardcoverOAuthException.InvalidResponse() }
    }
}
