package com.tortugapower.browserdrop

import com.tortugapower.browserdrop.internal.InterfaceAddresses
import com.tortugapower.browserdrop.internal.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.nio.file.Files
import java.util.Collections

/** The page as packaged: unit tests run with the module folder as the working directory. */
private val pageFolder = File("src/main/assets/browserdrop/page")

/** The device, faked: the packaged page, a fixed free space, one Wi-Fi address and no network changes. */
internal class FakePlatform(private val available: Long? = null) : Platform {
    override fun pageFile(name: String): ByteArray? = File(pageFolder, name).takeIf { it.isFile }?.readBytes()
    override fun availableBytes(folder: File): Long? = available
    override fun interfaceEntries() =
        listOf(InterfaceAddresses.Entry("wlan0", "192.168.1.5", InterfaceAddresses.PathKind.WIFI))
    override fun watchNetworks(onChange: () -> Unit) = AutoCloseable {}
}

internal fun makeTemporaryDirectory(): File = Files.createTempDirectory("BrowserDropTests-").toFile()

/** A server on any free port that accepts mp3 and m4b, staging in its own temporary folder. */
internal fun makeServer(
    available: Long? = null,
    configure: BrowserDropServer.Configuration.() -> BrowserDropServer.Configuration = { this }
): BrowserDropServer = BrowserDropServer(
    BrowserDropServer.Configuration(ports = listOf(0), allowedExtensions = setOf("mp3", "m4b")).configure(),
    makeTemporaryDirectory(),
    FakePlatform(available)
)

internal fun BrowserDropServer.runningPort(): Int =
    (state.value as? BrowserDropServer.State.Running)?.port ?: throw AssertionError("server isn't running: ${state.value}")

/** Polls until [condition] holds. */
internal suspend fun eventually(what: String = "condition", timeoutMs: Long = 3_000, condition: suspend () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return
        delay(20)
    }
    throw AssertionError("$what didn't happen within $timeoutMs ms")
}

/** Collects a server's events as they arrive. */
internal class EventRecorder(server: BrowserDropServer, scope: CoroutineScope) {
    private val storage = Collections.synchronizedList(mutableListOf<Event>())
    val job: Job = scope.launch(Dispatchers.IO) { server.events.collect { storage += it } }
    val events: List<Event> get() = synchronized(storage) { storage.toList() }
    operator fun contains(event: Event) = event in events
}

/** The names in a folder, sorted. */
internal fun names(folder: File): List<String> = folder.list()?.sorted() ?: throw AssertionError("can't list $folder")

/** Uploads the way the bundled page does (raw body, encoded path), minus the browser's `Origin`. */
internal fun upload(data: ByteArray, path: String, port: Int): Int {
    val encoded = URLEncoder.encode(path, "UTF-8").replace("+", "%20")
    val head = "POST /upload?path=$encoded HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Length: ${data.size}\r\n\r\n"
    return RawClient.status(rawRequest(port, head.toByteArray() + data)) ?: throw AssertionError("no response")
}

internal fun upload(text: String, path: String, port: Int) = upload(text.toByteArray(), path, port)

internal data class Response(val status: Int, val headers: Map<String, String>, val body: String)

internal fun get(path: String, port: Int): Response {
    val text = rawRequest(port, "GET $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n".toByteArray())
    val head = text.substringBefore("\r\n\r\n").split("\r\n")
    val headers = head.drop(1).associate { it.substringBefore(":").lowercase() to it.substringAfter(":").trim() }
    return Response(RawClient.status(text) ?: 0, headers, text.substringAfter("\r\n\r\n"))
}

internal fun rawRequest(port: Int, request: ByteArray): String = RawClient(port).use { client ->
    client.send(request)
    client.receiveAll().first
}

internal fun rawRequest(port: Int, request: String): String = rawRequest(port, request.toByteArray())

/** A bare TCP client, for what a browser won't send: a foreign `Host`, a missing length, a body cut short. */
internal class RawClient(port: Int) : AutoCloseable {
    private val socket = Socket("127.0.0.1", port)

    fun send(text: String) = send(text.toByteArray())

    fun send(data: ByteArray) {
        socket.getOutputStream().apply {
            write(data)
            flush()
        }
    }

    /** Reads until the text contains [marker] or the server closes. */
    fun receive(until: String, timeoutMs: Long = 3_000): String {
        val out = ByteArrayOutputStream()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (until !in out.toString(Charsets.UTF_8.name()) && System.currentTimeMillis() < deadline) {
            val (chunk, closed) = receiveChunk(deadline - System.currentTimeMillis())
            out.write(chunk)
            if (closed) break
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /** Reads until the server closes the connection; gives up after [timeoutMs]. */
    fun receiveAll(timeoutMs: Long = 3_000): Pair<String, Boolean> {
        val out = ByteArrayOutputStream()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val (chunk, closed) = receiveChunk(deadline - System.currentTimeMillis())
            out.write(chunk)
            if (closed) return out.toString(Charsets.UTF_8.name()) to true
        }
        return out.toString(Charsets.UTF_8.name()) to false
    }

    private fun receiveChunk(timeoutMs: Long): Pair<ByteArray, Boolean> {
        if (timeoutMs <= 0) return ByteArray(0) to false
        val buffer = ByteArray(65_536)
        return try {
            socket.soTimeout = timeoutMs.toInt()
            val count = socket.getInputStream().read(buffer)
            if (count < 0) ByteArray(0) to true else buffer.copyOf(count) to false
        } catch (e: SocketTimeoutException) {
            ByteArray(0) to false
        } catch (e: IOException) {
            ByteArray(0) to true // A reset counts as closed, too.
        }
    }

    override fun close() = socket.close()

    companion object {
        /** The final status of a response, skipping a `100 Continue` interim one. */
        fun status(response: String): Int? {
            val final = response.split("\r\n\r\n").firstOrNull { !it.startsWith("HTTP/1.1 100") } ?: return null
            return final.split(" ", limit = 3).getOrNull(1)?.toIntOrNull()
        }
    }
}
