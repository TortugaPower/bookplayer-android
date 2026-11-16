package com.tortugapower.browserdrop

import android.content.Context
import androidx.annotation.ColorInt
import com.tortugapower.browserdrop.internal.AndroidPlatform
import com.tortugapower.browserdrop.internal.InterfaceAddresses
import com.tortugapower.browserdrop.internal.PathRules
import com.tortugapower.browserdrop.internal.Platform
import com.tortugapower.browserdrop.internal.Staging
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One transfer session: an upload-only HTTP server that lives while the app's transfer screen is open. A
 * browser on the same network opens one of [addresses], drops files, and they land in staging; nothing leaves
 * it until [finish] hands the folder over. A closed server is never reused.
 *
 * Calls run in the order they're made, so the caller needs no queue of its own: a removal finishes before
 * [finish] takes the files, and a quick [pause] then [resume] pauses before it resumes.
 *
 * Mirrors SwiftBrowserDrop 2.0 (same wire protocol, same bundled page); see its README for the protocol.
 */
class BrowserDropServer internal constructor(
    configuration: Configuration,
    private val stagingRoot: File,
    internal val platform: Platform
) {
    constructor(context: Context, configuration: Configuration = Configuration()) : this(
        configuration,
        configuration.stagingRoot ?: defaultStagingRoot(context),
        AndroidPlatform(context.applicationContext)
    )

    internal val configuration = configuration.copy(
        allowedExtensions = configuration.allowedExtensions?.map { it.lowercase().trim('.') }?.toSet()
    )

    private val _state = MutableStateFlow<State>(State.Idle)
    private val _addresses = MutableStateFlow<List<Address>>(emptyList())
    private val _receivedFiles = MutableStateFlow<List<ReceivedFile>>(emptyList())
    private val _progress = MutableStateFlow<List<UploadProgress>>(emptyList())
    private val eventChannel = Channel<Event>(Channel.UNLIMITED)

    val state: StateFlow<State> = _state.asStateFlow()

    /** Usable addresses, best first (Wi-Fi, wired, hotspot, other). Empty unless running, or with no network. */
    val addresses: StateFlow<List<Address>> = _addresses.asStateFlow()

    /** Complete files in the order they finished; uploading the same path again replaces its entry. */
    val receivedFiles: StateFlow<List<ReceivedFile>> = _receivedFiles.asStateFlow()

    /** Uploads in flight, oldest first. Only the newest list is kept. */
    val progress: StateFlow<List<UploadProgress>> = _progress.asStateFlow()

    /** Every event, none dropped: a couple per upload, and [Event.Interrupted]. Single consumer; ends on close. */
    val events: Flow<Event> = eventChannel.receiveAsFlow()

    /** Orders the public calls. Never taken by the connections, so a call can wait for them to end. */
    private val calls = Mutex()

    /** Guards the state below, never held across a suspension or I/O. */
    internal val lock = Any()

    /**
     * Serializes what reads or changes staging: a check and its commit, a commit and a removal, each with its change
     * to the received files. Taken before [lock], never inside it.
     */
    internal val stagingLock = Any()

    internal var staging: Staging? = null
        private set

    /** Uploads in progress, for [progress] and the free-space check. */
    internal val inFlight = LinkedHashMap<Long, UploadProgress>()
    private var nextUploadId = 0L

    /** Connections being served, for [Configuration.maximumConnections]. */
    internal val activeConnections = AtomicInteger()

    private var listener: Listener? = null
    private var boundPort: Int? = null
    private var networkWatch: AutoCloseable? = null

    /**
     * Each connection blocks a thread while it waits for the browser. A view of its own past the default IO
     * pool's 64 threads, so a full house of idle connections can't starve the accept loop or the app.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    internal val dispatcher = Dispatchers.IO.limitedParallelism(this.configuration.maximumConnections + 4)

    /**
     * A bug while serving one request must cost that request, not the app: an exception no one catches in a
     * coroutine would otherwise reach the thread's handler and crash the process. The socket is closed by the
     * connection's own cleanup.
     */
    private val unexpectedErrors = CoroutineExceptionHandler { _, error -> error.printStackTrace() }
    internal val scope = CoroutineScope(SupervisorJob() + dispatcher + unexpectedErrors)

    // MARK: - Session

    /**
     * Creates staging and binds the first free port in [Configuration.ports]; returns the addresses to show.
     * @throws BrowserDropException.SessionClosed, BrowserDropException.AlreadyStarted,
     *   BrowserDropException.StagingUnavailable, BrowserDropException.CouldNotListen
     */
    suspend fun start(): List<Address> = calls.withLock {
        when (state.value) {
            State.Idle -> Unit
            State.Closed -> throw BrowserDropException.SessionClosed()
            else -> throw BrowserDropException.AlreadyStarted()
        }
        setState(State.Starting)

        if (staging == null) {
            val created = try {
                withContext(dispatcher) { Staging.create(stagingRoot) }
            } catch (e: IOException) {
                setState(State.Idle)
                throw BrowserDropException.StagingUnavailable()
            }
            synchronized(lock) { staging = created }
        }
        if (networkWatch == null) networkWatch = platform.watchNetworks { refreshAddresses() }

        val bound = try {
            listen(configuration.ports)
        } catch (e: BrowserDropException) {
            setState(State.Idle)
            throw e
        }
        becameRunning(bound)
    }

    /** Stops listening: uploads in flight are dropped, received files are kept. iOS: `suspend()`. */
    suspend fun pause(): Unit = calls.withLock {
        if (state.value !is State.Running) return@withLock
        val stopping = synchronized(lock) {
            _addresses.value = emptyList()
            _state.value = State.Paused
            listener.also { listener = null }
        }
        stopping?.stop()
    }

    /**
     * Listens again after [pause], trying the same port first so an open browser tab carries on.
     * @throws BrowserDropException.SessionClosed, BrowserDropException.NotPaused,
     *   BrowserDropException.CouldNotListen (the session stays paused)
     */
    suspend fun resume(): List<Address> = calls.withLock {
        when (state.value) {
            State.Paused -> Unit
            State.Closed -> throw BrowserDropException.SessionClosed()
            is State.Running -> return@withLock addresses.value
            else -> throw BrowserDropException.NotPaused()
        }
        setState(State.Starting)

        val previous = boundPort
        val ports = listOfNotNull(previous) + configuration.ports.filter { it != previous }
        val bound = try {
            listen(ports)
        } catch (e: BrowserDropException) {
            setState(State.Paused)
            throw e
        }
        becameRunning(bound)
    }

    /**
     * Closes the session, deletes partial uploads and returns the folder of complete files at their relative
     * paths (`Book/Disc 1/01.mp3`), or null when nothing arrived. The caller owns the folder from then on: move
     * it, import from it, delete it. It's inside the staging root, so [removeLeftovers] deletes it if not.
     * @throws BrowserDropException.SessionClosed
     */
    suspend fun finish(): File? = calls.withLock {
        if (state.value == State.Closed) throw BrowserDropException.SessionClosed()
        close()
        val finished = synchronized(lock) {
            _receivedFiles.value = emptyList()
            staging.also { staging = null }
        } ?: return@withLock null

        withContext(dispatcher) {
            finished.removeIncoming()
            // Only a folder known to be empty is deleted: if it can't be listed, it's handed over all the same.
            if (finished.isEmpty) {
                finished.remove()
                null
            } else {
                finished.files
            }
        }
    }

    /** Closes the session and deletes everything it received. Safe in any state. */
    suspend fun cancel(): Unit = calls.withLock {
        if (state.value == State.Closed) return@withLock
        close()
        val cancelled = synchronized(lock) {
            _receivedFiles.value = emptyList()
            staging.also { staging = null }
        }
        withContext(dispatcher) { cancelled?.remove() }
    }

    /**
     * Deletes a received file, or a folder and everything in it (`Book`), then the folders that leaves empty, so
     * [finish] won't hand it over. An upload still arriving at that path is added when it finishes.
     * @throws IOException when it couldn't be deleted
     */
    suspend fun removeReceivedItem(relativePath: String): Unit = calls.withLock {
        val current = synchronized(lock) { staging } ?: return@withLock
        val segments = PathRules.segments(relativePath) ?: return@withLock
        withContext(dispatcher) {
            // One step with the list, as an upload's commit is (always staging, then state), so no commit lands between.
            synchronized(stagingLock) {
                current.removeItem(segments.joinToString("/"))
                // Check the disk rather than the names: on a case-insensitive volume `book` removes `Book/01.mp3`.
                synchronized(lock) {
                    _receivedFiles.value = _receivedFiles.value.filter { current.containsFile(it.relativePath) }
                }
            }
        }
    }

    /** Stops listening and waits for every connection to end, so nothing writes to staging afterwards. */
    private suspend fun close() {
        val stopping = synchronized(lock) {
            _state.value = State.Closed
            _addresses.value = emptyList()
            listener.also { listener = null }
        }
        stopping?.stop()
        networkWatch?.close()
        networkWatch = null
        synchronized(lock) { _progress.value = emptyList() }
        eventChannel.close()
        scope.cancel()
    }

    private suspend fun becameRunning(bound: Listener): List<Address> {
        // ConnectivityManager answers over binder and NetworkInterface reads the kernel: off the caller's thread.
        val current = withContext(dispatcher) { InterfaceAddresses.addresses(platform.interfaceEntries(), bound.port) }
        synchronized(lock) {
            listener = bound
            boundPort = bound.port
            _state.value = State.Running(bound.port)
            _addresses.value = current
        }
        // Once the addresses are known: a connection is only served on one of them.
        bound.start()
        return current
    }

    private fun setState(state: State) {
        synchronized(lock) { _state.value = state }
    }

    internal fun emit(event: Event) {
        eventChannel.trySend(event)
    }

    /** Sends the uploads in flight to [progress], oldest first. Call holding [lock]. */
    internal fun publishProgress() {
        _progress.value = inFlight.values.sortedBy { it.upload.id }
    }

    internal fun nextUpload(relativePath: String, size: Long): Upload = synchronized(lock) {
        nextUploadId += 1
        Upload(nextUploadId, relativePath, size).also {
            inFlight[it.id] = UploadProgress(it, 0)
            publishProgress()
        }
    }

    /** Adds a finished upload, replacing an earlier one of the same path. Call holding [lock]. */
    internal fun addReceivedFile(file: ReceivedFile) {
        val files = _receivedFiles.value.toMutableList()
        val index = files.indexOfFirst { it.relativePath == file.relativePath }
        if (index >= 0) files[index] = file else files += file
        _receivedFiles.value = files
    }

    // MARK: - Listener

    /**
     * Binds the first port that's free. The first one gets up to a second: a socket that was just closed (this
     * session's, or the previous session's) can still hold its port for a moment, and binding then fails with
     * "address in use".
     */
    private suspend fun listen(ports: List<Int>): Listener {
        ports.forEachIndexed { index, port ->
            val deadline = System.nanoTime() + if (index == 0) 1_000_000_000L else 0L
            while (true) {
                try {
                    return Listener(this, bind(port))
                } catch (e: BindException) {
                    if (port == 0 || System.nanoTime() >= deadline) break
                    delay(20)
                } catch (e: IOException) {
                    break
                }
            }
        }
        throw BrowserDropException.CouldNotListen(ports)
    }

    private suspend fun bind(port: Int): ServerSocket = withContext(dispatcher) {
        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(port))
            socket
        } catch (e: IOException) {
            socket.close()
            throw e
        }
    }

    /** The listener ended without being stopped, for example after the system reclaimed its socket. */
    internal fun listenerStoppedOnItsOwn(stopped: Listener) {
        scope.launch {
            calls.withLock {
                val isCurrent = synchronized(lock) {
                    (listener === stopped && state.value is State.Running).also { current ->
                        if (current) {
                            listener = null
                            _addresses.value = emptyList()
                            _state.value = State.Paused
                        }
                    }
                }
                if (isCurrent) {
                    stopped.stop()
                    emit(Event.Interrupted)
                }
            }
        }
    }

    internal fun closeListenerSocketForTesting() {
        synchronized(lock) { listener }?.closeSocketForTesting()
    }

    // MARK: - Addresses

    private fun refreshAddresses() {
        val running = state.value as? State.Running ?: return
        val current = InterfaceAddresses.addresses(platform.interfaceEntries(), running.port)
        synchronized(lock) {
            if (state.value == running) _addresses.value = current
        }
    }

    // MARK: - Types

    data class Configuration(
        /**
         * Ports tried in order until one binds; `0` means any free port. Ports below 1024 are privileged on
         * Android, so unlike iOS there's no port 80 and the URL always shows the port.
         */
        val ports: List<Int> = listOf(8080, 8081, 0),
        /** Accepted extensions, lowercased and without the dot. Null accepts any type. */
        val allowedExtensions: Set<String>? = null,
        val maximumFileSize: Long = 8L shl 30,
        /** Free space that must remain after an upload is accepted. */
        val reservedFreeSpace: Long = 256L shl 20,
        /** How many uploads the page runs at once. Sent to the page in `config.json`. */
        val maximumConcurrentUploads: Int = 3,
        /**
         * A connection that sends nothing for this long is closed, and so is one that takes this long to send
         * its request head. A stalled upload fails as [FailureReason.TIMED_OUT].
         */
        val idleTimeout: Duration = 60.seconds,
        /** Connections served at once; more are closed right away, so the network can't exhaust our fds. */
        val maximumConnections: Int = 64,
        val pageSettings: PageSettings = PageSettings(),
        /** Null means [defaultStagingRoot]. [removeLeftovers] empties it. */
        val stagingRoot: File? = null
    )

    /** What `config.json` tells the page. */
    data class PageSettings(
        /** Page and window title, for example the app's name. */
        val title: String? = null,
        /** BCP 47 language of [strings], for example `es`. Sets the page's `lang`. */
        val language: String? = null,
        /** Sent as `#RRGGBB`; alpha is ignored. */
        @ColorInt val accentColor: Int? = null,
        /** Replacements for the page's English text, keyed as in the SwiftBrowserDrop README. */
        val strings: Map<String, String> = emptyMap()
    )

    /** Where the session is in its life. Once [Closed], make a new server. */
    sealed interface State {
        /** Created, [start] not called yet. */
        data object Idle : State
        /** Binding a port. */
        data object Starting : State
        /** Accepting uploads on [port]. */
        data class Running(val port: Int) : State
        /** Not listening, session kept. [resume] listens again on the same port. iOS: `suspended`. */
        data object Paused : State
        /** Finished or cancelled. The server holds nothing any more. */
        data object Closed : State
    }

    companion object {
        /**
         * `noBackupFilesDir/browserdrop`. Not `cacheDir`: the system can clear that under storage pressure in
         * the middle of a session.
         */
        fun defaultStagingRoot(context: Context): File = File(context.noBackupFilesDir, "browserdrop")

        /**
         * Removes staging left by a session that never finished or cancelled (the app was killed, for example),
         * and any handed-over folder the app didn't move or delete. Call it at launch, before creating a server.
         *
         * Safe on the main thread: the root is renamed out of the way, one call however much it holds, and its
         * files are deleted on a background thread. A server started right after gets a new, empty root.
         */
        fun removeLeftovers(context: Context) = removeLeftovers(defaultStagingRoot(context))

        /** [removeLeftovers] for a custom [Configuration.stagingRoot]. */
        fun removeLeftovers(stagingRoot: File) = removeLeftovers(stagingRoot) { delete ->
            thread(name = "BrowserDrop leftovers", isDaemon = true) { delete() }
        }

        /** [removeLeftovers], with the deletion handed to [inBackground]. */
        internal fun removeLeftovers(stagingRoot: File, inBackground: (() -> Unit) -> Unit) {
            val parent = stagingRoot.absoluteFile.parentFile ?: return
            val trashPrefix = "${stagingRoot.name}.trash-"
            if (stagingRoot.exists()) {
                val trash = File(parent, trashPrefix + UUID.randomUUID())
                if (!stagingRoot.renameTo(trash)) {
                    // Not renamed: what's in it now is deleted where it is, still in the background. A server
                    // started meanwhile makes a session folder of its own, which isn't in this list.
                    val leftovers = stagingRoot.listFiles().orEmpty()
                    inBackground { leftovers.forEach { it.deleteRecursively() } }
                }
            }
            // This launch's trash, and any an earlier launch was killed before it finished deleting.
            val trash = parent.listFiles { file -> file.name.startsWith(trashPrefix) }.orEmpty()
            if (trash.isNotEmpty()) inBackground { trash.forEach { it.deleteRecursively() } }
        }
    }
}
