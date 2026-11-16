package com.tortugapower.audiobookplayer.viewmodel

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.tortugapower.audiobookplayer.logic.ImportArchiveUtils
import com.tortugapower.browserdrop.Address
import com.tortugapower.browserdrop.BrowserDropException
import com.tortugapower.browserdrop.BrowserDropServer
import com.tortugapower.browserdrop.Event
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/**
 * One Transfer from Computer session, one per opening of the Transfer sheet (iOS `TransferViewModel`). A computer
 * on the same network opens the address shown and uploads files, which wait in the server's staging and are
 * listed like an import. [TransferViewModel] keeps the current one across configuration changes.
 *
 * Done hands them straight to the import ([importReceived]), so the import sheet never opens and the library
 * goes on to its placement prompt. Cancel, or the sheet going away, throws them away.
 *
 * The server's calls run in [sessionScope], which outlives the session's state ([stateScope], cancelled when it
 * ends): Done or Cancel end the session after the sheet is gone, and the server must still close its sockets. The
 * server keeps the calls in the order they're made, so a removal finishes before Done takes the files.
 */
class TransferSession(
    private val server: BrowserDropServer,
    /** Imports into a library folder (null = root) what the session's `finish` hands over. */
    private val importReceived: (targetFolderPath: String?, receive: suspend () -> File?) -> Unit,
    private val appLifecycle: Lifecycle = ProcessLifecycleOwner.get().lifecycle,
    private val sessionScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
) {

    /** STARTING until listening, and again whenever it stops (background, interruption) until it's back. */
    enum class Status { STARTING, RUNNING }

    /** A row of the list: a file at the top, or a folder with how many files it holds. [id] is its path. */
    data class Row(val id: String, val title: String, val isFolder: Boolean, val fileCount: Int = 1)

    /** What the sheet shows; it stops following the server once the session ends. */
    private val stateScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _status = MutableStateFlow(Status.STARTING)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** The address to show, best first; null until listening, or when this device has no network to share. */
    val address: StateFlow<Address?> = server.addresses.map { it.firstOrNull() }
        .stateIn(stateScope, SharingStarted.Eagerly, null)

    val rows: StateFlow<List<Row>> = server.receivedFiles.map { files -> rows(files.map { it.relativePath }) }
        .stateIn(stateScope, SharingStarted.Eagerly, emptyList())

    /** Files Done imports, inside folders too. */
    val fileCount: StateFlow<Int> = server.receivedFiles.map { it.size }
        .stateIn(stateScope, SharingStarted.Eagerly, 0)

    private var hasEnded = false

    /**
     * A cached app is frozen or killed whenever the system likes, mid-upload included, so the server stops
     * listening in the background, as on iOS; files that arrived stay. The process lifecycle, not the activity's, so a configuration change
     * doesn't drop uploads in flight. Registering replays ON_START, which starts the session.
     */
    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) = listen()
        override fun onStop(owner: LifecycleOwner) {
            notListening()
            sessionScope.launch { server.pause() }
        }
    }

    init {
        sessionScope.launch {
            server.events.collect { event ->
                if (event !is Event.Interrupted) return@collect
                notListening()
                // Back while in the foreground; in the background, coming back resumes it.
                if (appLifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) listen()
            }
        }
        appLifecycle.addObserver(lifecycleObserver)
    }

    /** A row's remove: drop that file, or that folder and its files, from what Done imports. */
    fun remove(rowId: String) {
        sessionScope.launch {
            try {
                server.removeReceivedItem(rowId)
            } catch (e: java.io.IOException) {
                Log.e(TAG, "Transfer couldn't remove an item", e)
            }
        }
    }

    /**
     * Done: hand what arrived to the import, into [targetFolderPath] (the folder being browsed). The import
     * runs on its own, so the sheet can close right away.
     */
    fun confirm(targetFolderPath: String?) {
        if (!end()) return
        importReceived(targetFolderPath) { server.finish() }
    }

    /** Cancel: throw away what arrived. */
    fun discard() {
        if (!end()) return
        sessionScope.launch { server.cancel() }
    }

    /** Marks the session over, once. Returns false if it already was. */
    private fun end(): Boolean {
        if (hasEnded) return false
        hasEnded = true
        appLifecycle.removeObserver(lifecycleObserver)
        stateScope.cancel()
        return true
    }

    /**
     * The server stopped listening and has no address to show: the spinner, not "Connect to Wi‑Fi", until [listen]
     * has it back.
     */
    private fun notListening() {
        if (!hasEnded) _status.value = Status.STARTING
    }

    /** Starts the session the first time, and listens again after the background or an interruption. */
    private fun listen() {
        if (hasEnded) return
        sessionScope.launch {
            try {
                if (server.state.value == BrowserDropServer.State.Idle) server.start() else server.resume()
                _status.value = Status.RUNNING
            } catch (e: BrowserDropException) {
                // Nothing on screen: the browser can't reach the address, and coming back to the app tries
                // again. A first start that fails keeps the spinner (iOS parity).
                Log.e(TAG, "Transfer couldn't listen", e)
            }
        }
    }

    companion object {
        private const val TAG = "TransferSession"

        /**
         * Rows for the received files' relative paths: a file at the top is its own row, and a file in a folder
         * counts toward that folder's row. Sorted by title, as the import list is.
         */
        fun rows(paths: List<String>): List<Row> {
            val files = mutableListOf<Row>()
            val folderCounts = linkedMapOf<String, Int>()
            for (path in paths) {
                val folder = path.substringBefore('/', missingDelimiterValue = "")
                if (folder.isEmpty()) files += Row(path, path, isFolder = false)
                else folderCounts[folder] = (folderCounts[folder] ?: 0) + 1
            }
            val folders = folderCounts.map { (folder, count) -> Row(folder, folder, isFolder = true, fileCount = count) }
            return (files + folders).sortedWith(compareBy(ImportArchiveUtils.naturalOrderComparator) { it.title })
        }
    }
}
