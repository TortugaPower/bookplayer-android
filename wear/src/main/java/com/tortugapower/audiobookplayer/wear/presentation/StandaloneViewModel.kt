package com.tortugapower.audiobookplayer.wear.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tortugapower.audiobookplayer.core.CoreContext
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.logic.DownloadUnitStatus
import com.tortugapower.audiobookplayer.logic.LibraryContentsSync
import com.tortugapower.audiobookplayer.logic.OfflineDownloadManager
import com.tortugapower.audiobookplayer.logic.SyncTaskFactory
import com.tortugapower.audiobookplayer.logic.sort.EffectiveSort
import com.tortugapower.audiobookplayer.logic.sort.LibrarySortManager
import com.tortugapower.audiobookplayer.repository.LibraryRepository
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import com.tortugapower.audiobookplayer.wear.sync.WearSyncServiceHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Discrete download state for a row glyph (the live progress value is read separately from taskProgress). */
enum class DownloadUiState { NotDownloaded, Downloading, Downloaded }

// One book file's DownloadUnitStatus lives in `:core` (com.tortugapower.audiobookplayer.logic) — the
// shared per-unit derivation for phone and Wear.

/**
 * A single library row. [isFolder] decides the tap action: folders drill in, books/bound books play.
 * [id] is the folder's/book's `relativePath` (navigation + play/download key), falling back to `uuid`. Books
 * also carry playback progress ([percentCompleted] 0..1, [isFinished]) + [durationSeconds] for the detail
 * line, [downloadState] for the cloud/downloading/watch glyph, and [downloadUuids] (the book files' uuids)
 * so the row can look up live download progress for its bar. Mirrors the iOS PRO watch list.
 */
data class LibraryRow(
    val id: String,
    val title: String,
    val author: String,
    val isFolder: Boolean,
    val percentCompleted: Double = 0.0,
    val isFinished: Boolean = false,
    val durationSeconds: Double = 0.0,
    val downloadState: DownloadUiState = DownloadUiState.NotDownloaded,
    // Whole-book download progress inputs (mirrors iOS's aggregate): total book files, how many are already
    // on disk, and the uuids of the not-yet-downloaded ones (so the bar sums their live progress). Overall
    // fraction = (downloadedUnits + Σ live progress of downloadUuids) / totalUnits — 0→50%→100% for a
    // 2-file bound book, not a per-file reset.
    val totalUnits: Int = 0,
    val downloadedUnits: Int = 0,
    val downloadUuids: List<String> = emptyList(),
)

/**
 * [loaded] distinguishes "first load still in flight" (the stateIn seed) from "loaded and actually empty",
 * so the screen never flashes the empty message before the pipeline's first real emission — the root-level
 * cold-start gate watches a different (raw repository) flow and can lift before THIS one has data.
 */
data class StandaloneUiState(val rows: List<LibraryRow> = emptyList(), val loaded: Boolean = false)

/**
 * Backs one level of the standalone (PRO) library — the folder at [path] (null = root). PRO users get the
 * full hierarchy (folders navigable, books/bound books play + download for offline). Observes the shared
 * `:core` library repository, and derives each row's download state from disk (file exists = downloaded) +
 * the sync task queue (queued/running = downloading), reacting to task-status changes rather than the
 * per-chunk progress map (that drives only the bar, read in the UI). Enqueues a contents-fetch on open +
 * refresh, and download/cancel/remove actions via [OfflineDownloadManager].
 */
class StandaloneViewModel(
    private val libraryRepository: LibraryRepository,
    private val syncTaskRepository: SyncTaskRepository,
    private val path: String? = null,
    private val librarySortManager: LibrarySortManager? = null,
) : ViewModel() {

    private val appContext get() = CoreContext.appContext
    // Bumped after a "remove download" so the (non-reactive) file-existence check re-runs and the glyph flips.
    private val removeTrigger = MutableStateFlow(0)

    private val itemsFlow =
        if (path == null) libraryRepository.getRootItems() else libraryRepository.getItemsInPath(path)

    // Order is a VIEW transform, same as the phone's list: while this level's sort is automatic we
    // order by the rule and ignore orderRank (the prefs arrive via the preference-fetch task into the
    // watch's own DataStore). No manager (tests) ⇒ rank order.
    private val sortedItemsFlow: Flow<List<LibraryItemEntity>> =
        librarySortManager?.let { manager ->
            combine(itemsFlow, manager.observeEffectiveSort(path)) { items, sort ->
                when (sort) {
                    is EffectiveSort.Automatic -> sort.sortType.sorted(items)
                    EffectiveSort.Custom -> items
                }
            }
        } ?: itemsFlow

    // Per-item download "units" (the book files), resolved off-main when the library changes and cached, so
    // a BOUND book's sub-book query doesn't re-run on every task/queue emission.
    private val rowSources: Flow<List<RowSource>> = sortedItemsFlow.map { items ->
        items.map { item ->
            val units = if (item.type == ItemType.FOLDER) {
                emptyList()
            } else {
                OfflineDownloadManager.downloadUnits(libraryRepository, item)
            }
            RowSource(item, units.map { UnitRef(it.uuid, it.relativePath) })
        }
    }.flowOn(Dispatchers.IO)

    val state: StateFlow<StandaloneUiState> = combine(
        rowSources,
        syncTaskRepository.getAllTasks(),
        removeTrigger,
    ) { sources, tasks, _ ->
        StandaloneUiState(sources.map { buildRow(it, tasks) }, loaded = true)
    }.flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StandaloneUiState())

    init {
        enqueueFetch(force = false)
    }

    /** Manual refresh: force a re-fetch of this folder, bypassing the on-open throttle. */
    fun refresh() = enqueueFetch(force = true)

    fun download(row: LibraryRow) = withItem(row) { item ->
        OfflineDownloadManager.startDownload(appContext, libraryRepository, syncTaskRepository, item)
        WearSyncServiceHost.start(appContext) // ensure the sync engine is running to execute the task
    }

    fun cancelDownload(row: LibraryRow) = withItem(row) { item ->
        OfflineDownloadManager.cancelDownload(libraryRepository, syncTaskRepository, item)
    }

    fun removeDownload(row: LibraryRow) = withItem(row) { item ->
        OfflineDownloadManager.removeDownload(appContext, libraryRepository, item)
        removeTrigger.update { it + 1 } // atomic: guarantee one rebuild per removal even under concurrency
    }

    private fun withItem(row: LibraryRow, block: suspend (LibraryItemEntity) -> Unit) {
        // Off-main: the download/remove blocks do small disk IO (file exists/delete, URL resolution).
        viewModelScope.launch(Dispatchers.IO) {
            libraryRepository.getItemByPath(row.id)?.let { block(it) }
        }
    }

    private fun enqueueFetch(force: Boolean) {
        viewModelScope.launch {
            // Prefs ride along with the contents fetch (same open/refresh cadence the phone uses);
            // the factory debounces to one pull per 30s per launch.
            SyncTaskFactory.createFetchPreferencesTask(syncTaskRepository, force = force)
            SyncTaskFactory.createFetchContentsTask(syncTaskRepository, path = path, force = force)
        }
    }

    /** Build the row with its discrete download state (disk + task queue); folders carry no download state. */
    private fun buildRow(source: RowSource, tasks: List<SyncTaskEntity>): LibraryRow {
        val base = toRow(source.item) { entity ->
            // Localize a container's bare-count author ("N Files"/"N Chapters") for the watch list.
            LibraryContentsSync.displayDetails(appContext, entity.type, entity.author).orEmpty()
        }
        if (source.item.type == ItemType.FOLDER) return base
        // Shared `:core` per-unit derivation (disk truth + task queue, partial-file rule included) —
        // the exact same statuses the phone library rows aggregate over.
        val statuses = source.units.map { unit ->
            OfflineDownloadManager.unitStatus(appContext, unit.uuid, unit.relativePath, tasks)
        }
        return base.copy(
            downloadState = deriveDownloadState(statuses),
            totalUnits = source.units.size,
            downloadedUnits = statuses.count { it.downloaded },
            // Only the not-yet-downloaded files need live progress; already-downloaded ones count as whole
            // units in the aggregate, so a completed file stays at its share instead of resetting the bar.
            downloadUuids = source.units.filterIndexed { i, _ -> !statuses[i].downloaded }.map { it.uuid },
        )
    }

    private data class RowSource(val item: LibraryItemEntity, val units: List<UnitRef>)
    private data class UnitRef(val uuid: String, val relativePath: String?)

    companion object {
        /**
         * Discrete download state for a row from its book files (pure, unit-tested): all files present ⇒
         * Downloaded; else any file queued/running ⇒ Downloading; else NotDownloaded (incl. a partial set,
         * so re-tapping Download fetches the rest — `startDownload` skips already-local files).
         */
        fun deriveDownloadState(units: List<DownloadUnitStatus>): DownloadUiState = when {
            units.isEmpty() -> DownloadUiState.NotDownloaded
            units.all { it.downloaded } -> DownloadUiState.Downloaded
            units.any { it.taskActive } -> DownloadUiState.Downloading
            else -> DownloadUiState.NotDownloaded
        }

        // Whole-book download progress moved to :core — OfflineDownloadManager.downloadProgressFraction —
        // so the phone library rows share the exact same aggregation.

        /** Pure entity → row mapping (unit-tested). id = relativePath (nav/play key) or uuid fallback.
         *  [formatAuthor] localizes a container's bare-count author (displayDetails at the call site). */
        fun toRow(
            item: LibraryItemEntity,
            formatAuthor: (LibraryItemEntity) -> String = { it.author.orEmpty() },
        ): LibraryRow = LibraryRow(
            id = item.relativePath ?: item.uuid,
            title = item.title,
            author = formatAuthor(item),
            isFolder = item.type == ItemType.FOLDER,
            percentCompleted = item.percentCompleted,
            isFinished = item.isFinished,
            durationSeconds = item.duration,
        )

        /**
         * The playback-progress prefix for a book's detail line (pure, unit-tested), mirroring iOS
         * `RemoteItemListCellView.percentCompleted`: "" when unstarted, "100% - " when finished, else
         * "N% - " (percentCompleted is 0..1 on Android). The duration is appended by the screen.
         */
        fun progressPrefix(percentCompleted: Double, isFinished: Boolean): String = when {
            isFinished -> "100% - "
            percentCompleted > 0.0 -> "${(percentCompleted * 100).toInt()}% - "
            else -> ""
        }
    }
}
