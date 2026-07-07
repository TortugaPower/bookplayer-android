package com.tortugapower.audiobookplayer.wear.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.logic.SyncTaskFactory
import com.tortugapower.audiobookplayer.repository.LibraryRepository
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * A single library row. [isFolder] decides the tap action: folders drill in, books/bound books play.
 * [id] is the folder's/book's `relativePath` (navigation + play key), falling back to `uuid`.
 */
data class LibraryRow(val id: String, val title: String, val author: String, val isFolder: Boolean)

data class StandaloneUiState(val rows: List<LibraryRow> = emptyList())

/**
 * Backs one level of the standalone (PRO) library — the folder at [path] (null = root). Unlike the
 * free/remote tier (a flat recents list where every tap plays), PRO users get the full library hierarchy:
 * folders are navigable, books/bound books play. Observes the shared `:core` library repository so the
 * list reflects whatever the watch's own sync ([com.tortugapower.audiobookplayer.wear.sync.WearSyncServiceHost])
 * has pulled into Room, and enqueues a contents-fetch for [path] on open + manual refresh (the fetch runs in
 * the sync foreground service; this only enqueues the task — `SyncStatusManager` throttles it).
 */
class StandaloneViewModel(
    private val libraryRepository: LibraryRepository,
    private val syncTaskRepository: SyncTaskRepository,
    private val path: String? = null,
) : ViewModel() {

    private val itemsFlow =
        if (path == null) libraryRepository.getRootItems() else libraryRepository.getItemsInPath(path)

    val state: StateFlow<StandaloneUiState> = itemsFlow
        .map { items -> StandaloneUiState(items.map(::toRow)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StandaloneUiState())

    init {
        // Enqueue a fetch for this folder so a freshly-signed-in watch (or a not-yet-synced folder) fills
        // in. Throttled inside SyncTaskFactory/SyncStatusManager, so it is safe on every VM creation.
        enqueueFetch(force = false)
    }

    /** Manual refresh: force a re-fetch of this folder, bypassing the on-open throttle. */
    fun refresh() = enqueueFetch(force = true)

    private fun enqueueFetch(force: Boolean) {
        viewModelScope.launch {
            SyncTaskFactory.createFetchContentsTask(syncTaskRepository, path = path, force = force)
        }
    }

    companion object {
        /** Pure entity → row mapping (unit-tested). id = relativePath (nav/play key) or uuid fallback. */
        fun toRow(item: LibraryItemEntity): LibraryRow = LibraryRow(
            id = item.relativePath ?: item.uuid,
            title = item.title,
            author = item.author.orEmpty(),
            isFolder = item.type == ItemType.FOLDER,
        )
    }
}
