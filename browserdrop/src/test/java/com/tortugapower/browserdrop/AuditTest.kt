package com.tortugapower.browserdrop

import com.tortugapower.browserdrop.internal.PathRules
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Ported from SwiftBrowserDrop's `AuditTests`: each asserts the safe behavior; a failure confirms a defect. */
class AuditTest {
    /** Every entry in receivedFiles must exist as a regular file in staging. */
    private fun ghostEntries(server: BrowserDropServer): List<String> {
        val staging = server.staging ?: return emptyList()
        return server.receivedFiles.value.map { it.relativePath }.filterNot { File(staging.files, it).isFile }
    }

    // A1: a file uploaded where a folder of earlier uploads is would replace the folder's files. Refused instead.
    @Test
    fun aFileWhereAFolderIsIsRefused() = runBlocking {
        val server = makeServer()
        val recorder = EventRecorder(server, this)
        server.start()
        val port = server.runningPort()
        assertEquals(201, upload("chapter", "Book.mp3/01.mp3", port))
        assertEquals(409, upload("x", "Book.mp3", port))

        assertEquals("chapter", File(server.staging!!.files, "Book.mp3/01.mp3").readText())
        assertEquals(listOf("Book.mp3/01.mp3"), server.receivedFiles.value.map { it.relativePath })
        assertEquals(emptyList<String>(), ghostEntries(server))
        eventually("conflict reported") { Event.UploadFailed("Book.mp3", FailureReason.PATH_CONFLICT) in recorder }
        server.cancel()
        recorder.job.join()
    }

    // A1b: the reverse: an upload into a folder whose name an earlier file has.
    @Test
    fun aFolderWhereAFileIsIsRefused() = runBlocking {
        val server = makeServer()
        server.start()
        val port = server.runningPort()
        assertEquals(201, upload("x", "Book.mp3", port))
        assertEquals(409, upload("chapter", "Book.mp3/Disc 1/01.mp3", port))

        val staging = server.staging!!
        assertEquals("x", File(staging.files, "Book.mp3").readText())
        assertEquals(listOf("Book.mp3"), server.receivedFiles.value.map { it.relativePath })
        assertEquals(emptyList<String>(), names(staging.incoming))
        server.cancel()
    }

    // A1c: the name is free when the upload starts, but another one takes it before this one is stored.
    @Test
    fun aNameTakenWhileUploadingIsRefusedWhenStored() = runBlocking {
        val server = makeServer()
        server.start()
        val port = server.runningPort()

        RawClient(port).use { client ->
            client.send("POST /upload?path=Book.mp3/01.mp3 HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Length: 10\r\n\r\n")
            client.send(ByteArray(5))
            eventually("upload in flight") { synchronized(server.lock) { server.inFlight.isNotEmpty() } }

            assertEquals(201, upload("x", "Book.mp3", port))
            client.send(ByteArray(5))
            assertEquals(409, RawClient.status(client.receive(until = "\r\n\r\n")))
        }

        assertEquals(listOf("Book.mp3"), server.receivedFiles.value.map { it.relativePath })
        assertEquals(emptyList<String>(), names(server.staging!!.incoming))
        server.cancel()
    }

    // A4: on a case-insensitive volume (a Mac's, where these tests usually run) two paths differing only in case
    // are one name. Android's internal storage is case-sensitive, where both are simply stored.
    @Test
    fun aNameInAnotherCaseIsRefusedOnACaseInsensitiveVolume() = runBlocking {
        val server = makeServer()
        server.start()
        assumeTrue("the temporary volume is case-sensitive", server.staging!!.namesIgnoreCase)
        val port = server.runningPort()

        assertEquals(201, upload("one", "a.mp3", port))
        assertEquals(409, upload("two", "A.mp3", port))
        assertEquals(201, upload("three", "Book/01.mp3", port))
        assertEquals(409, upload("four", "book/02.mp3", port))

        assertEquals(listOf("a.mp3", "Book/01.mp3"), server.receivedFiles.value.map { it.relativePath })
        val folder = checkNotNull(server.finish())
        assertEquals(listOf("Book", "a.mp3"), names(folder))
        assertEquals("one", File(folder, "a.mp3").readText())
    }

    // A6: header slowloris: one line per (idleTimeout - e) keeps a connection that never sent a request.
    @Test
    fun aHeadThatNeverEndsIsClosed() = runBlocking {
        val server = makeServer { copy(idleTimeout = 300.milliseconds) }
        server.start()
        val port = server.runningPort()
        var closedEarly = false
        RawClient(port).use { client ->
            client.send("GET / HTTP/1.1\r\n")
            for (i in 0 until 20) { // 3 s, 10x the idle timeout
                try {
                    client.send("X-A: b\r\n")
                } catch (e: IOException) {
                    closedEarly = true
                    break
                }
                if (client.receiveAll(timeoutMs = 150).second) {
                    closedEarly = true
                    break
                }
            }
        }
        assertTrue("connection kept open 10x idleTimeout without ever completing a request", closedEarly)
        server.cancel()
    }

    // A7: connections past the cap are closed right away, and the ones within it keep being served.
    @Test
    fun connectionsPastTheCapAreClosed() = runBlocking {
        val server = makeServer { copy(idleTimeout = 30.seconds, maximumConnections = 8) }
        server.start()
        val port = server.runningPort()

        val held = List(8) { RawClient(port) }
        eventually("connections held") { server.activeConnections.get() == 8 }
        RawClient(port).use { extra ->
            assertTrue("connection over the cap wasn't closed", extra.receiveAll(timeoutMs = 2_000).second)
        }
        held.forEach { it.close() }
        eventually("connections released") { server.activeConnections.get() == 0 }
        assertEquals(200, get("/config.json", port).status)
        server.cancel()
    }

    // A8: a consumer that falls behind loses no events.
    @Test
    fun uploadFinishedEventsAreNotDropped() = runBlocking {
        val server = makeServer()
        for (index in 0 until 1_000) {
            val upload = Upload(index.toLong(), "f$index.mp3", 1)
            server.emit(Event.UploadStarted(upload))
            server.emit(Event.UploadFinished(ReceivedFile(upload.relativePath, 1)))
        }
        val recorder = EventRecorder(server, this) // Attaches late, as a busy main thread would catch up.
        server.cancel() // Ends the flow.
        recorder.job.join()
        assertEquals(1_000, recorder.events.count { it is Event.UploadFinished })
    }

    // A9: a refused upload with Expect: 100-continue must not hold the connection for the whole idle timeout.
    @Test
    fun refusedExpectContinueUploadIsClosedPromptly() = runBlocking {
        val server = makeServer { copy(idleTimeout = 5.seconds) }
        server.start()
        val port = server.runningPort()
        RawClient(port).use { client ->
            client.send(
                "POST /upload?path=a.txt HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Length: 1000\r\nExpect: 100-continue\r\n\r\n"
            )
            val (text, closed) = client.receiveAll(timeoutMs = 4_000)
            assertEquals(400, RawClient.status(text))
            assertTrue("connection still held after the 400", closed)
        }
        server.cancel()
    }

    // A10: one slow connection must not block others.
    @Test
    fun anIdleConnectionDoesNotBlockOthers() = runBlocking {
        val server = makeServer()
        server.start()
        val port = server.runningPort()
        RawClient(port).use { idle ->
            idle.send("POST /upload?path=a.mp3 HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Length: 100000\r\n\r\n")
            val start = System.currentTimeMillis()
            assertEquals(200, get("/config.json", port).status)
            assertTrue(System.currentTimeMillis() - start < 1_000)
        }
        server.cancel()
    }

    // A11: a valid-by-rules deep path can be stored.
    @Test
    fun pathsTheRulesAcceptCanBeStored() = runBlocking {
        val server = makeServer()
        server.start()
        val segment = "d".repeat(200)
        val path = List(4) { segment }.joinToString("/") + "/f.mp3" // ~810 bytes < 1024
        assertNotNull(PathRules.segments(path))
        assertEquals(201, upload("x", path, server.runningPort()))
        server.cancel()
    }

    // The interrupted path: a socket that closes on its own pauses the session and says so.
    @Test
    fun aListenerThatStopsOnItsOwnPausesTheSession() = runBlocking {
        val server = makeServer()
        val recorder = EventRecorder(server, this)
        server.start()
        val port = server.runningPort()
        upload("a", "a.mp3", port)

        server.closeListenerSocketForTesting()
        eventually("interrupted reported") { Event.Interrupted in recorder }
        assertEquals(BrowserDropServer.State.Paused, server.state.value)
        assertTrue(server.addresses.value.isEmpty())

        server.resume()
        delay(10)
        assertEquals(201, upload("b", "b.mp3", server.runningPort()))
        assertEquals(listOf("a.mp3", "b.mp3"), server.receivedFiles.value.map { it.relativePath })
        server.cancel()
        recorder.job.join()
    }
}
