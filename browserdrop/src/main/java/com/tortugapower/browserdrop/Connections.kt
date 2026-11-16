package com.tortugapower.browserdrop

import com.tortugapower.browserdrop.internal.ContentType
import com.tortugapower.browserdrop.internal.HttpRequestHead
import com.tortugapower.browserdrop.internal.HttpResponse
import com.tortugapower.browserdrop.internal.InterfaceAddresses
import com.tortugapower.browserdrop.internal.PageConfig
import com.tortugapower.browserdrop.internal.PathRules
import com.tortugapower.browserdrop.internal.RequestRules
import com.tortugapower.browserdrop.internal.Staging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * One bound port. The accept loop and every connection are children of [scope]; [stop] closes the sockets
 * (blocking reads don't heed coroutine cancellation) and returns once they have all ended.
 */
internal class Listener(private val server: BrowserDropServer, private val serverSocket: ServerSocket) {
    val port: Int = serverSocket.localPort

    /** Set before any socket is closed, so a read that fails afterwards knows the session stopped it. */
    @Volatile
    var stopped = false
        private set

    // The server's context (dispatcher, error handler) under a supervisor of its own.
    private val scope = CoroutineScope(server.scope.coroutineContext + SupervisorJob(server.scope.coroutineContext.job))
    private val sockets = mutableSetOf<Socket>()
    private lateinit var acceptJob: Job

    fun start() {
        acceptJob = scope.launch { acceptLoop() }
    }

    private fun acceptLoop() {
        val maximum = server.configuration.maximumConnections
        while (true) {
            val socket = try {
                serverSocket.accept()
            } catch (e: IOException) {
                break
            }
            if (!register(socket)) break
            // Only on an address the app shows (or loopback): not on cellular, nor a global IPv6 address.
            if (!InterfaceAddresses.isShown(socket.localAddress, server.addresses.value)) {
                unregister(socket)
                continue
            }
            // Over the cap: closed right away, so other devices on the network can't exhaust our descriptors.
            if (server.activeConnections.incrementAndGet() > maximum) {
                server.activeConnections.decrementAndGet()
                unregister(socket)
                continue
            }
            scope.launch {
                try {
                    Connection(server, this@Listener, socket).serve()
                } catch (e: IOException) {
                    // The socket failed before the request was read: nothing to answer, nothing stored.
                } finally {
                    server.activeConnections.decrementAndGet()
                    unregister(socket)
                }
            }
        }
        if (!stopped) server.listenerStoppedOnItsOwn(this)
    }

    private fun register(socket: Socket): Boolean = synchronized(sockets) {
        if (stopped) {
            socket.closeQuietly()
            false
        } else {
            sockets += socket
            true
        }
    }

    private fun unregister(socket: Socket) {
        synchronized(sockets) { sockets -= socket }
        socket.closeQuietly()
    }

    /** Closes the listening socket as the system would when it reclaims it: not a [stop]. */
    fun closeSocketForTesting() = serverSocket.closeQuietly()

    /** Stops accepting, drops every connection, and returns once they've all ended. */
    suspend fun stop() {
        val open = synchronized(sockets) {
            stopped = true
            sockets.toList()
        }
        serverSocket.closeQuietly()
        open.forEach { it.closeQuietly() }
        acceptJob.join()
        // Only the accept loop starts connections, so once it has returned this list is complete.
        scope.coroutineContext.job.children.toList().forEach { it.join() }
        scope.cancel()
    }
}

/** Serves one request on one connection, then returns; the listener closes the socket. */
internal class Connection(
    private val server: BrowserDropServer,
    private val listener: Listener,
    private val socket: Socket
) {
    private val configuration = server.configuration
    private val idleTimeoutMs = configuration.idleTimeout.inWholeMilliseconds
    private val input = socket.getInputStream()
    private val readBuffer = ByteArray(RECEIVE_CHUNK)

    /** What one read from the browser gave. */
    private sealed interface Chunk {
        class Data(val bytes: ByteArray, val count: Int) : Chunk
        /** The browser closed the connection. */
        data object Closed : Chunk
        /** The session stopped: pause, finish or cancel. */
        data object Cancelled : Chunk
        /** Nothing arrived for the idle timeout. */
        data object TimedOut : Chunk
    }

    fun serve() {
        // The whole head has to arrive within the idle timeout, not just each piece of it.
        val headDeadline = System.nanoTime() + idleTimeoutMs * 1_000_000
        var buffer = ByteArray(0)
        while (true) {
            val end = indexOfHeadEnd(buffer)
            if (end >= 0) {
                if (end > HttpRequestHead.MAXIMUM_SIZE) {
                    send(HttpResponse.error(431, "headers_too_large", "Request headers are too large."))
                    return
                }
                val head = HttpRequestHead.parse(buffer.copyOfRange(0, end))
                if (head == null) {
                    send(HttpResponse.error(400, "bad_request", "The request couldn't be read."))
                    return
                }
                route(head, buffer.copyOfRange(end + 4, buffer.size))
                return
            }
            if (buffer.size > HttpRequestHead.MAXIMUM_SIZE) {
                send(HttpResponse.error(431, "headers_too_large", "Request headers are too large."))
                return
            }
            val chunk = receive(headDeadline) as? Chunk.Data ?: return
            buffer += chunk.bytes.copyOfRange(0, chunk.count)
        }
    }

    private fun route(head: HttpRequestHead, body: ByteArray) {
        val unread = maxOf(0L, (head.contentLength ?: 0L) - body.size)
        when {
            head.method == "POST" && head.path == "/upload" -> receiveUpload(head, body)
            head.method == "GET" && head.path == "/config.json" ->
                send(HttpResponse.rawJson(200, PageConfig.json(configuration)))
            head.method == "GET" -> send(pageFileResponse(head.path))
            else -> refuse(
                HttpResponse.error(405, "method_not_allowed", "Only GET and POST /upload are supported."), unread
            )
        }
    }

    // MARK: - Uploads

    private fun receiveUpload(head: HttpRequestHead, body: ByteArray) {
        val unread = maxOf(0L, (head.contentLength ?: 0L) - body.size)
        val host = head.headers["host"]

        if (!RequestRules.isLocalHost(host) || !RequestRules.isSameOrigin(head.headers["origin"], host)) {
            refuse(HttpResponse.error(403, "forbidden", "Uploads must come from this device's own page."), unread)
            return
        }
        val size = head.contentLength
        if (head.headers["transfer-encoding"] != null || size == null) {
            send(HttpResponse.error(411, "length_required", "Send the file with a Content-Length."))
            return
        }
        val rawPath = head.query["path"]
        if (rawPath == null) {
            refuse(HttpResponse.error(400, "invalid_path", "The path query parameter is missing."), unread)
            return
        }

        val relativePath = when (val result = PathRules.uploadPath(rawPath, configuration.allowedExtensions)) {
            is PathRules.Result.Accepted -> result.path
            is PathRules.Result.Refused -> {
                val response = when (result.rejection) {
                    PathRules.Rejection.UNSUPPORTED_TYPE ->
                        HttpResponse.error(400, "unsupported_type", "This file type isn't accepted.")
                    PathRules.Rejection.INVALID_PATH ->
                        HttpResponse.error(400, "invalid_path", "The path isn't a valid relative path.")
                }
                refuse(response, unread)
                return
            }
        }

        val staging = synchronized(server.lock) { server.staging }
        if (staging != null && synchronized(server.stagingLock) { staging.conflicts(relativePath) }) {
            server.emit(Event.UploadFailed(relativePath, FailureReason.PATH_CONFLICT))
            refuse(PATH_CONFLICT, unread)
            return
        }
        if (size > configuration.maximumFileSize) {
            server.emit(Event.UploadFailed(relativePath, FailureReason.TOO_LARGE))
            refuse(HttpResponse.error(413, "too_large", "The file is larger than this device accepts."), unread)
            return
        }
        if (staging == null || !hasRoom(size, staging)) {
            server.emit(Event.UploadFailed(relativePath, FailureReason.INSUFFICIENT_STORAGE))
            refuse(INSUFFICIENT_STORAGE, unread)
            return
        }
        val incomingFile = try {
            staging.makeIncomingFile()
        } catch (e: IOException) {
            server.emit(Event.UploadFailed(relativePath, FailureReason.WRITE_FAILED))
            refuse(WRITE_FAILED, unread)
            return
        }

        val upload = server.nextUpload(relativePath, size)
        try {
            server.emit(Event.UploadStarted(upload))
            if (head.expectsContinue && body.isEmpty() && size > 0) {
                try {
                    socket.getOutputStream().apply {
                        write(HttpResponse.CONTINUE_INTERIM)
                        flush()
                    }
                } catch (e: IOException) {
                    // The body read below finds the connection gone.
                }
            }

            val file = ReceivedFile(relativePath, size)
            val failure = streamBody(upload, file, body, incomingFile, staging)
            // Out of the in-flight list before the final event, so no list shows it once it's finished or failed.
            removeFromInFlight(upload)

            if (failure != null) {
                incomingFile.delete()
                server.emit(Event.UploadFailed(relativePath, failure))
                when (failure) {
                    FailureReason.INSUFFICIENT_STORAGE -> send(INSUFFICIENT_STORAGE)
                    FailureReason.WRITE_FAILED -> send(WRITE_FAILED)
                    FailureReason.PATH_CONFLICT -> send(PATH_CONFLICT)
                    else -> Unit // The connection is gone or the session stopped: nobody to answer.
                }
                return
            }

            server.emit(Event.UploadFinished(file))
            send(HttpResponse.json(201, mapOf("path" to relativePath)))
        } finally {
            // Normally it left the list before its final event (above); this covers every other way out.
            removeFromInFlight(upload)
        }
    }

    /**
     * Streams the body into [incomingFile] and stores it as [file], adding it to the received files; returns why it
     * failed, or null for a stored file.
     */
    private fun streamBody(
        upload: Upload,
        file: ReceivedFile,
        body: ByteArray,
        incomingFile: File,
        staging: Staging
    ): FailureReason? {
        val size = upload.size
        val progressStep = maxOf(512L shl 10, size / 100)
        var received = 0L
        var reported = 0L
        var pending: ByteArray? = body
        var pendingCount = body.size
        var failure: FailureReason? = null

        try {
            FileOutputStream(incomingFile).use { output ->
                while (received < size) {
                    if (pending == null || pendingCount == 0) {
                        when (val chunk = receive()) {
                            is Chunk.Data -> {
                                pending = chunk.bytes
                                pendingCount = chunk.count
                            }
                            Chunk.Closed -> failure = FailureReason.DISCONNECTED
                            Chunk.Cancelled -> failure = FailureReason.CANCELLED
                            Chunk.TimedOut -> failure = FailureReason.TIMED_OUT
                        }
                        if (failure != null) break
                    }
                    val count = minOf(pendingCount.toLong(), size - received).toInt()
                    try {
                        output.write(pending!!, 0, count)
                    } catch (e: IOException) {
                        failure = if (isOutOfSpace(e)) FailureReason.INSUFFICIENT_STORAGE else FailureReason.WRITE_FAILED
                        break
                    }
                    pending = null
                    pendingCount = 0
                    received += count
                    synchronized(server.lock) {
                        server.inFlight[upload.id] = UploadProgress(upload, received)
                        if (received < size && received - reported >= progressStep) {
                            reported = received
                            server.publishProgress()
                        }
                    }
                }
            }
        } catch (e: IOException) {
            // Closing the file flushes it, and can fail like any write.
            if (failure == null) failure = if (isOutOfSpace(e)) FailureReason.INSUFFICIENT_STORAGE else FailureReason.WRITE_FAILED
        }
        if (failure != null) return failure

        // Checked again here, where it can't race: another upload may have taken the name meanwhile. Listed in the
        // same step, so a removal can't come between them and leave a row with no file.
        return try {
            synchronized(server.stagingLock) {
                staging.commit(incomingFile, upload.relativePath)
                synchronized(server.lock) {
                    // Out of the in-flight list as it joins the received ones, so no list shows it twice.
                    if (server.inFlight.remove(upload.id) != null) server.publishProgress()
                    server.addReceivedFile(file)
                }
            }
            null
        } catch (e: Staging.PathConflict) {
            FailureReason.PATH_CONFLICT
        } catch (e: IOException) {
            FailureReason.WRITE_FAILED
        }
    }

    private fun removeFromInFlight(upload: Upload) {
        synchronized(server.lock) {
            if (server.inFlight.remove(upload.id) != null) server.publishProgress()
        }
    }

    /** Whether [size] more bytes leave the reserve free, counting what the uploads in flight still have to write. */
    private fun hasRoom(size: Long, staging: Staging): Boolean {
        val available = server.platform.availableBytes(staging.files) ?: return true
        val pending = synchronized(server.lock) { server.inFlight.values.sumOf { it.upload.size - it.receivedBytes } }
        return available - pending - size >= configuration.reservedFreeSpace
    }

    // MARK: - Page

    private fun pageFileResponse(path: String): HttpResponse {
        val notFound = HttpResponse.error(404, "not_found", "There's nothing here.")
        val relative = if (path == "/") "index.html" else path.drop(1)
        // The same rules as uploads: no `..`, no hidden or absolute paths, so nothing outside the page is reached.
        val segments = PathRules.segments(relative) ?: return notFound
        val name = segments.joinToString("/")
        val bytes = server.platform.pageFile(name)
            ?: return if (name == "index.html") {
                HttpResponse.error(500, "page_missing", "The upload page is missing from the package.")
            } else {
                notFound
            }
        return HttpResponse(200, ContentType.forExtension(name.substringAfterLast('.', "")), bytes, "no-cache").apply {
            headers += "Content-Security-Policy" to PAGE_POLICY
        }
    }

    // MARK: - Reading and answering

    /** The next chunk the browser sends, or why there isn't one. [deadlineNanos] cuts the idle wait short. */
    private fun receive(deadlineNanos: Long? = null): Chunk {
        val timeoutMs = deadlineNanos?.let { minOf(idleTimeoutMs, (it - System.nanoTime()) / 1_000_000) } ?: idleTimeoutMs
        if (timeoutMs <= 0) return Chunk.TimedOut
        return try {
            socket.soTimeout = timeoutMs.toInt()
            val count = input.read(readBuffer)
            when {
                listener.stopped -> Chunk.Cancelled
                count < 0 -> Chunk.Closed
                else -> Chunk.Data(readBuffer, count)
            }
        } catch (e: SocketTimeoutException) {
            Chunk.TimedOut
        } catch (e: IOException) {
            if (listener.stopped) Chunk.Cancelled else Chunk.Closed
        }
    }

    /** Sends a final response and closes our side of the connection. */
    private fun send(response: HttpResponse) {
        try {
            socket.getOutputStream().apply {
                write(response.serialized())
                flush()
            }
            socket.shutdownOutput()
        } catch (e: IOException) {
            // The browser is gone.
        }
    }

    /**
     * Answers a request we won't serve, then reads and discards the rest of its body (up to [DRAIN_LIMIT]):
     * closing with unread data makes the browser see a reset instead of our answer.
     */
    private fun refuse(response: HttpResponse, unread: Long) {
        send(response)
        if (unread <= 0 || unread > DRAIN_LIMIT) return
        val deadline = System.nanoTime() + idleTimeoutMs * 1_000_000
        var left = unread
        while (left > 0) {
            val chunk = receive(deadline) as? Chunk.Data ?: return
            left -= chunk.count
        }
    }

    private fun indexOfHeadEnd(buffer: ByteArray): Int {
        for (i in 0..buffer.size - 4) {
            if (buffer[i] == CR && buffer[i + 1] == LF && buffer[i + 2] == CR && buffer[i + 3] == LF) return i
        }
        return -1
    }

    private fun isOutOfSpace(e: IOException): Boolean {
        val message = generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }.joinToString(" ")
        return "ENOSPC" in message || "No space left" in message
    }

    companion object {
        /** Bodies of refused requests are read and discarded up to this size, so the browser sees the response. */
        const val DRAIN_LIMIT = 8L shl 20
        const val RECEIVE_CHUNK = 256 * 1024
        private const val CR = '\r'.code.toByte()
        private const val LF = '\n'.code.toByte()

        val PATH_CONFLICT = HttpResponse.error(
            409, "path_conflict", "Another file or folder sent in this session already has this name."
        )
        val INSUFFICIENT_STORAGE =
            HttpResponse.error(507, "insufficient_storage", "There isn't enough free space on this device.")
        val WRITE_FAILED = HttpResponse.error(500, "write_failed", "The file couldn't be written.")

        val PAGE_POLICY = listOf(
            "default-src 'none'", "script-src 'self'", "style-src 'self'", "img-src 'self' data:",
            "connect-src 'self'", "base-uri 'none'", "form-action 'none'", "frame-ancestors 'none'"
        ).joinToString("; ")
    }
}

internal fun java.io.Closeable.closeQuietly() {
    try {
        close()
    } catch (e: IOException) {
        // Nothing to do.
    }
}
