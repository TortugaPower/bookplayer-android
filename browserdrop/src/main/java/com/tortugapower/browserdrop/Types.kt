package com.tortugapower.browserdrop

/** An address the page can be opened at from a computer on the same network. */
data class Address(
    val kind: Kind,
    /** The interface name, for example `wlan0`. Shown for diagnostics only. */
    val interfaceName: String,
    /** IPv4 address, for example `192.168.1.20`. */
    val host: String,
    val port: Int
) {
    enum class Kind {
        WIFI,
        WIRED,
        /** The device's own hotspot. Computers joined to it use this address. */
        HOTSPOT,
        OTHER
    }

    /** `http://192.168.1.20:8080/`, with the port only when it isn't 80. */
    val url: String get() = if (port == 80) "http://$host/" else "http://$host:$port/"
}

/** A file being uploaded. [id] tells two uploads of the same path apart. */
data class Upload(
    val id: Long,
    /** The cleaned relative path, for example `Book/Disc 1/01.mp3`. */
    val relativePath: String,
    /** The size the browser announced. */
    val size: Long
)

/** A finished upload waiting in staging until [BrowserDropServer.finish]. */
data class ReceivedFile(val relativePath: String, val size: Long) {
    /** The last path segment, for example `01.mp3`. */
    val name: String get() = relativePath.substringAfterLast('/')
}

/**
 * How much of an upload has arrived. [BrowserDropServer.progress] lists one per upload in flight, updated
 * whenever one of them starts, ends or advances about 1% of its size (at least 512 KiB).
 */
data class UploadProgress(val upload: Upload, val receivedBytes: Long)

sealed interface Event {
    /** The request was accepted and the body is arriving. [BrowserDropServer.progress] follows it. */
    data class UploadStarted(val upload: Upload) : Event

    /** The file is complete and in [BrowserDropServer.receivedFiles], replacing an upload of the same path. */
    data class UploadFinished(val file: ReceivedFile) : Event

    /**
     * The upload ended without a file. It can come without [UploadStarted] when the file was refused up
     * front: for a name that's taken, its size, or lack of space.
     */
    data class UploadFailed(val relativePath: String, val reason: FailureReason) : Event

    /**
     * The socket stopped on its own. The session is kept and the state is [BrowserDropServer.State.Paused]:
     * call [BrowserDropServer.resume] to listen again on the same port.
     */
    data object Interrupted : Event
}

enum class FailureReason {
    /** The browser went away before sending the whole file. */
    DISCONNECTED,
    /** The server stopped it: [BrowserDropServer.pause], [BrowserDropServer.finish] or [BrowserDropServer.cancel]. */
    CANCELLED,
    /** Nothing arrived for [BrowserDropServer.Configuration.idleTimeout]. */
    TIMED_OUT,
    /** Larger than [BrowserDropServer.Configuration.maximumFileSize]. */
    TOO_LARGE,
    /** Accepting it would leave less than [BrowserDropServer.Configuration.reservedFreeSpace] free. */
    INSUFFICIENT_STORAGE,
    /** The file couldn't be written to staging. */
    WRITE_FAILED,
    /**
     * Something else this session received already has that name: a folder where the file goes, or a file
     * where one of its folders goes. Only an earlier upload of exactly the same path is replaced.
     */
    PATH_CONFLICT
}

sealed class BrowserDropException(message: String) : Exception(message) {
    /** The session was finished or cancelled. Create a new server. */
    class SessionClosed : BrowserDropException("The session is closed")
    /** [BrowserDropServer.start] was already called. */
    class AlreadyStarted : BrowserDropException("The session was already started")
    /** [BrowserDropServer.resume] needs a paused session. */
    class NotPaused : BrowserDropException("The session isn't paused")
    /** None of the ports could be bound. */
    class CouldNotListen(val ports: List<Int>) : BrowserDropException("Couldn't listen on any of $ports")
    /** The staging folder couldn't be created. */
    class StagingUnavailable : BrowserDropException("The staging folder couldn't be created")
}
