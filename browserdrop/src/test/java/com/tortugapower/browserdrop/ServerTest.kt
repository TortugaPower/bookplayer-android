package com.tortugapower.browserdrop

import com.tortugapower.browserdrop.BrowserDropServer.State
import com.tortugapower.browserdrop.internal.Staging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.Collections
import kotlin.time.Duration.Companion.milliseconds

/** Ported from SwiftBrowserDrop's `ServerTests`: the real server on 127.0.0.1, spoken to over TCP. */
class ServerTest {
    // MARK: - Session

    @Test
    fun keepsUploadsInStagingUntilFinish() = runBlocking {
        val server = makeServer()
        server.start()
        val port = server.runningPort()

        assertEquals(201, upload("one", "Book/Disc 1/01.mp3", port))
        assertEquals(201, upload("two", "Book/02.mp3", port))
        assertEquals(201, upload("loose", "loose.m4b", port))
        assertEquals(
            listOf("Book/02.mp3", "Book/Disc 1/01.mp3", "loose.m4b"),
            server.receivedFiles.value.map { it.relativePath }.sorted()
        )
        val incoming = server.staging!!.incoming

        val folder = checkNotNull(server.finish())
        assertEquals(listOf("Book", "loose.m4b"), names(folder))
        assertEquals("one", File(folder, "Book/Disc 1/01.mp3").readText())
        assertFalse(incoming.exists())
        assertEquals(State.Closed, server.state.value)
        assertTrue(server.receivedFiles.value.isEmpty())
        assertNull(server.staging)
    }

    @Test
    fun finishHandsOverNothingWhenNothingArrived() = runBlocking {
        val server = makeServer()
        server.start()
        val staging = server.staging!!

        assertNull(server.finish())
        assertFalse(staging.files.exists())
        assertFalse(staging.incoming.exists())
        assertThrows<BrowserDropException.SessionClosed> { server.finish() }
    }

    @Test
    fun uploadingAPathAgainReplacesTheFile() = runBlocking {
        val server = makeServer()
        server.start()
        val port = server.runningPort()

        assertEquals(201, upload("first", "a.mp3", port))
        assertEquals(201, upload("second!", "a.mp3", port))
        assertEquals(listOf(ReceivedFile("a.mp3", 7)), server.receivedFiles.value)

        val folder = checkNotNull(server.finish())
        assertEquals("second!", File(folder, "a.mp3").readText())
    }

    @Test
    fun pauseAndResumeKeepThePortAndTheFiles() = runBlocking {
        val server = makeServer()
        server.start()
        val port = server.runningPort()
        upload("a", "a.mp3", port)

        server.pause()
        assertEquals(State.Paused, server.state.value)
        assertTrue(server.addresses.value.isEmpty())
        try {
            upload("b", "b.mp3", port)
            fail("uploaded while paused")
        } catch (e: IOException) {
            // Connection refused.
        }

        server.resume()
        assertEquals(port, server.runningPort())
        assertEquals(201, upload("b", "b.mp3", port))
        assertEquals(listOf("a.mp3", "b.mp3"), server.receivedFiles.value.map { it.relativePath })
        server.cancel()
    }

    /** Back-to-back restarts also catch the moment a closed socket still holds its port. */
    @Test
    fun quickRestartsKeepThePort() = runBlocking {
        val server = makeServer()
        server.start()
        val port = server.runningPort()

        repeat(25) {
            server.pause()
            server.resume()
            assertEquals(port, server.runningPort())
        }
        delay(300)

        assertEquals(State.Running(port), server.state.value)
        assertEquals(201, upload("a", "a.mp3", port))
        server.cancel()
    }

    /** Closing the transfer screen and opening it again straight away must give the same address. */
    @Test
    fun aNewSessionGetsThePortTheLastOneJustReleased() = runBlocking {
        val first = makeServer()
        first.start()
        val port = first.runningPort()
        first.cancel()

        val second = makeServer { copy(ports = listOf(port, 0)) }
        second.start()
        assertEquals(port, second.runningPort())
        second.cancel()
    }

    @Test
    fun callsRunInTheOrderTheyreMade() = runBlocking {
        val server = makeServer()
        server.start()
        val port = server.runningPort()
        upload("a", "a.mp3", port)

        // Made one after the other without waiting, as a screen's button taps are: the removal, which suspends
        // on disk I/O, must still finish before finish takes the files.
        launch { server.removeReceivedItem("a.mp3") }
        val finished = async { server.finish() }
        assertNull(finished.await())
    }

    @Test
    fun startingTwiceOrResumingWhileRunningIsReported() = runBlocking {
        val server = makeServer()
        server.start()
        assertThrows<BrowserDropException.AlreadyStarted> { server.start() }
        assertEquals(server.addresses.value, server.resume())
        server.cancel()
        assertThrows<BrowserDropException.SessionClosed> { server.resume() }
    }

    @Test
    fun reportsTheAddressesItIsReachableAt() = runBlocking {
        val server = makeServer()
        val addresses = server.start()
        val port = server.runningPort()
        assertEquals(listOf(Address(Address.Kind.WIFI, "wlan0", "192.168.1.5", port)), addresses)
        assertEquals(addresses, server.addresses.value)
        server.cancel()
        assertTrue(server.addresses.value.isEmpty())
    }

    @Test
    fun removedItemsAreNotHandedOver() = runBlocking {
        val server = makeServer()
        server.start()
        val port = server.runningPort()
        upload("a", "loose.mp3", port)
        upload("b", "Book/01.mp3", port)
        upload("c", "Book/02.mp3", port)
        upload("d", "Other/Disc 1/01.mp3", port)

        server.removeReceivedItem("Book")
        server.removeReceivedItem("Other/Disc 1/01.mp3")
        assertEquals(listOf("loose.mp3"), server.receivedFiles.value.map { it.relativePath })

        // The emptied "Other" folder is gone too, so it isn't handed over as an empty item.
        val folder = checkNotNull(server.finish())
        assertEquals(listOf("loose.mp3"), names(folder))
    }

    @Test
    fun aRemovedPathCanBeUploadedAgain() = runBlocking {
        val server = makeServer()
        server.start()
        val port = server.runningPort()
        upload("a", "a.mp3", port)
        server.removeReceivedItem("a.mp3")
        assertTrue(server.receivedFiles.value.isEmpty())

        assertEquals(201, upload("again", "a.mp3", port))
        assertEquals(listOf(ReceivedFile("a.mp3", 5)), server.receivedFiles.value)
        server.cancel()
    }

    @Test
    fun cancelDeletesEverything() = runBlocking {
        val server = makeServer()
        server.start()
        upload("a", "a.mp3", server.runningPort())
        val staging = server.staging!!
        assertTrue(staging.files.exists())

        server.cancel()
        assertEquals(State.Closed, server.state.value)
        assertFalse(staging.files.exists())
        assertFalse(staging.incoming.exists())
        assertThrows<BrowserDropException.SessionClosed> { server.start() }
        server.cancel() // Safe again.
    }

    @Test
    fun removeLeftoversEmptiesTheStagingRoot() {
        val parent = makeTemporaryDirectory()
        val root = File(parent, "browserdrop")
        Staging.create(root)
        // Trash an earlier launch was killed before it finished deleting.
        File(parent, "browserdrop.trash-earlier/Book").mkdirs()
        val unrelated = File(parent, "other").apply { mkdirs() }

        val deferred = mutableListOf<() -> Unit>()
        BrowserDropServer.removeLeftovers(root) { deferred += it }
        // Out of the way at once, so a new session starts empty; nothing deleted yet.
        assertFalse(root.exists())
        assertEquals(2, parent.list()!!.count { it.startsWith("browserdrop.trash-") })

        deferred.forEach { it() }
        assertEquals(listOf("other"), parent.list()!!.toList())
        assertTrue(unrelated.exists())
    }

    // MARK: - Uploads

    @Test
    fun reportsAnUploadFromStartToFinish() = runBlocking {
        val server = makeServer()
        val recorder = EventRecorder(server, this)
        val snapshots = Collections.synchronizedList(mutableListOf<List<UploadProgress>>())
        val progress = launch(Dispatchers.IO) { server.progress.collect { snapshots += it } }
        server.start()
        val port = server.runningPort()

        // Sent in two halves, waiting in between, so the upload is seen in flight whatever the timing.
        val size = 2 shl 20
        RawClient(port).use { client ->
            client.send("POST /upload?path=Book/big.mp3 HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Length: $size\r\n\r\n")
            client.send(ByteArray(size / 2))
            eventually("upload in progress") {
                server.progress.value.firstOrNull()?.let {
                    it.upload.relativePath == "Book/big.mp3" && it.receivedBytes >= 512 shl 10
                } ?: false
            }
            client.send(ByteArray(size / 2))
            assertEquals(201, RawClient.status(client.receive(until = "\r\n\r\n")))
        }

        val file = ReceivedFile("Book/big.mp3", size.toLong())
        eventually("finished event") { Event.UploadFinished(file) in recorder }
        eventually("upload left the progress list") { server.progress.value.isEmpty() }

        val events = recorder.events
        val started = (events.first() as Event.UploadStarted).upload
        assertEquals("Book/big.mp3", started.relativePath)
        assertEquals(size.toLong(), started.size)
        assertEquals(listOf(Event.UploadStarted(started), Event.UploadFinished(file)), events)
        val received = synchronized(snapshots) { snapshots.flatten() }.filter { it.upload == started }.map { it.receivedBytes }
        assertEquals(received.sorted(), received)
        progress.cancel()
        server.cancel()
        recorder.job.join()
    }

    @Test
    fun anAbortedUploadLeavesNothingBehind() = runBlocking {
        val server = makeServer()
        val recorder = EventRecorder(server, this)
        server.start()
        val port = server.runningPort()

        RawClient(port).use { client ->
            client.send("POST /upload?path=a.mp3 HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Length: 1000\r\n\r\n")
            client.send(ByteArray(100))
            eventually("upload started") { recorder.events.any { it is Event.UploadStarted } }
        }

        eventually("failure reported") { Event.UploadFailed("a.mp3", FailureReason.DISCONNECTED) in recorder }
        assertTrue(server.receivedFiles.value.isEmpty())
        assertEquals(emptyList<String>(), names(server.staging!!.incoming))
        server.cancel()
        recorder.job.join()
    }

    @Test
    fun pausingDropsUploadsInFlight() = runBlocking {
        val server = makeServer()
        val recorder = EventRecorder(server, this)
        server.start()
        val port = server.runningPort()

        RawClient(port).use { client ->
            client.send("POST /upload?path=a.mp3 HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Length: 1000\r\n\r\n")
            client.send(ByteArray(100))
            eventually("upload started") { recorder.events.any { it is Event.UploadStarted } }
            server.pause()
        }
        eventually("cancel reported") { Event.UploadFailed("a.mp3", FailureReason.CANCELLED) in recorder }
        assertTrue(server.progress.value.isEmpty())
        assertEquals(emptyList<String>(), names(server.staging!!.incoming))
        server.cancel()
        recorder.job.join()
    }

    @Test
    fun closesConnectionsThatGoQuiet() = runBlocking {
        val server = makeServer { copy(idleTimeout = 200.milliseconds) }
        val recorder = EventRecorder(server, this)
        server.start()
        val port = server.runningPort()

        RawClient(port).use { client ->
            client.send("POST /upload?path=a.mp3 HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Length: 10\r\n\r\nabc")
            val (_, closed) = client.receiveAll(timeoutMs = 3_000)
            assertTrue(closed)
        }
        eventually("timeout reported") { Event.UploadFailed("a.mp3", FailureReason.TIMED_OUT) in recorder }
        server.cancel()
        recorder.job.join()
    }

    @Test
    fun refusesPathsThatCouldEscape() = runBlocking {
        val server = makeServer()
        server.start()
        val port = server.runningPort()
        for (path in listOf("../x.mp3", "/abs.mp3", "a/../b.mp3", ".hidden/x.mp3", "a//b.mp3", "a\u0000.mp3")) {
            assertEquals(path, 400, upload("x", path, port))
        }
        assertTrue(server.receivedFiles.value.isEmpty())
        server.cancel()
    }

    @Test
    fun refusesTypesThatArentAllowed() = runBlocking {
        val server = makeServer()
        server.start()
        assertEquals(400, upload("x", "notes.txt", server.runningPort()))
        server.cancel()
    }

    @Test
    fun refusesFilesOverTheSizeLimit() = runBlocking {
        val server = makeServer { copy(maximumFileSize = 10) }
        val recorder = EventRecorder(server, this)
        server.start()
        val port = server.runningPort()

        assertEquals(413, upload(ByteArray(11), "a.mp3", port))
        assertEquals(201, upload(ByteArray(10), "b.mp3", port))
        eventually("too large reported") { Event.UploadFailed("a.mp3", FailureReason.TOO_LARGE) in recorder }
        server.cancel()
        recorder.job.join()
    }

    @Test
    fun refusesUploadsThatWouldFillTheDevice() = runBlocking {
        val server = makeServer(available = 1_000) { copy(reservedFreeSpace = 500) }
        val recorder = EventRecorder(server, this)
        server.start()
        val port = server.runningPort()

        assertEquals(507, upload(ByteArray(600), "a.mp3", port))
        assertEquals(201, upload(ByteArray(400), "b.mp3", port))
        eventually("no space reported") { Event.UploadFailed("a.mp3", FailureReason.INSUFFICIENT_STORAGE) in recorder }
        server.cancel()
        recorder.job.join()
    }

    // MARK: - Request checks

    @Test
    fun refusesUploadsFromOtherOrigins() = runBlocking {
        val server = makeServer()
        server.start()
        val port = server.runningPort()

        assertEquals(403, RawClient.status(rawUpload(port, "Host: 127.0.0.1:$port", "Origin: http://evil.example")))
        assertEquals(201, RawClient.status(rawUpload(port, "Host: 127.0.0.1:$port", "Origin: http://127.0.0.1:$port")))
        server.cancel()
    }

    /** DNS rebinding: a website points its own domain at the device, and the browser sends that domain as `Host`. */
    @Test
    fun refusesPublicHostNames() = runBlocking {
        val server = makeServer()
        server.start()
        val port = server.runningPort()
        assertEquals(403, RawClient.status(rawUpload(port, "Host: evil.example:$port", "Origin: http://evil.example:$port")))
        server.cancel()
    }

    @Test
    fun requiresAContentLength() = runBlocking {
        val server = makeServer()
        server.start()
        val port = server.runningPort()

        assertEquals(411, RawClient.status(rawRequest(port, "POST /upload?path=a.mp3 HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")))
        val chunked = rawRequest(
            port,
            "POST /upload?path=a.mp3 HTTP/1.1\r\nHost: 127.0.0.1\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n0\r\n\r\n"
        )
        assertEquals(411, RawClient.status(chunked))
        server.cancel()
    }

    @Test
    fun honorsExpectContinue() = runBlocking {
        val server = makeServer()
        server.start()
        val port = server.runningPort()

        RawClient(port).use { client ->
            client.send("POST /upload?path=a.mp3 HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 5\r\nExpect: 100-continue\r\n\r\n")
            val interim = client.receive(until = "\r\n\r\n")
            assertTrue(interim, interim.startsWith("HTTP/1.1 100 Continue"))
            client.send("hello")
            val (response, _) = client.receiveAll()
            assertEquals(201, RawClient.status(interim + response))
        }
        assertEquals(listOf("a.mp3"), server.receivedFiles.value.map { it.relativePath })
        server.cancel()
    }

    @Test
    fun refusesMalformedRequests() = runBlocking {
        val server = makeServer()
        server.start()
        val port = server.runningPort()
        assertEquals(405, RawClient.status(rawRequest(port, "BREW /pot HTTP/1.1\r\nHost: x\r\n\r\n")))
        assertEquals(400, RawClient.status(rawRequest(port, "GET / HTTP/9.9\r\n\r\n")))
        assertEquals(431, RawClient.status(rawRequest(port, "GET / HTTP/1.1\r\nX-Big: ${"a".repeat(17 * 1024)}\r\n\r\n")))
        server.cancel()
    }

    /** Refused bodies are drained, so the browser reads the JSON error instead of a connection reset. */
    @Test
    fun refusedBodiesAreDrained() = runBlocking {
        val server = makeServer()
        server.start()
        val port = server.runningPort()
        val body = ByteArray(1 shl 20)
        val response = rawRequest(
            port,
            "POST /upload?path=notes.txt HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray() + body
        )
        assertEquals(400, RawClient.status(response))
        assertTrue(response, response.contains("\"error\":\"unsupported_type\""))
        server.cancel()
    }

    // MARK: - Page

    @Test
    fun servesTheBundledPage() = runBlocking {
        val server = makeServer()
        server.start()
        val port = server.runningPort()

        val page = get("/", port)
        assertEquals(200, page.status)
        assertTrue(page.headers["content-type"]!!.startsWith("text/html"))
        assertTrue(page.headers["content-security-policy"]!!.contains("script-src 'self'"))
        assertEquals("nosniff", page.headers["x-content-type-options"])
        assertEquals("DENY", page.headers["x-frame-options"])
        assertTrue(page.body.contains("/app.js"))
        assertEquals(200, get("/app.js", port).status)
        assertEquals(200, get("/app.css", port).status)
        assertEquals(404, get("/missing.js", port).status)
        server.cancel()
    }

    @Test
    fun describesItselfInConfigJson() = runBlocking {
        val server = makeServer {
            copy(maximumFileSize = 1_234, pageSettings = BrowserDropServer.PageSettings(title = "My App", language = "es"))
        }
        server.start()
        val config = get("/config.json", server.runningPort())
        assertEquals(200, config.status)
        assertEquals("no-store", config.headers["cache-control"])
        assertEquals(
            """{"allowedExtensions":["m4b","mp3"],"language":"es","maximumConcurrentUploads":3,""" +
                """"maximumFileSize":1234,"protocolVersion":1,"strings":{},"title":"My App"}""",
            config.body
        )
        server.cancel()
    }

    @Test
    fun pageFilesStayInsideTheirFolder() = runBlocking {
        val server = makeServer()
        server.start()
        val port = server.runningPort()
        for (path in listOf("/../build.gradle.kts", "/%2e%2e/build.gradle.kts", "/.hidden", "/a//b")) {
            val response = rawRequest(port, "GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
            assertEquals(path, 404, RawClient.status(response))
            assertFalse(path, response.contains("plugins"))
        }
        server.cancel()
    }

    // MARK: - Helpers

    private fun rawUpload(port: Int, vararg headers: String): String {
        val head = (listOf("POST /upload?path=a.mp3 HTTP/1.1") + headers + "Content-Length: 3").joinToString("\r\n")
        return rawRequest(port, "$head\r\n\r\nabc")
    }
}

internal suspend inline fun <reified T : Throwable> assertThrows(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: Throwable) {
        if (e is T) return
        throw AssertionError("expected ${T::class.simpleName}, got $e", e)
    }
    throw AssertionError("expected ${T::class.simpleName}, nothing was thrown")
}
