package com.tortugapower.audiobookplayer.logic.sort

import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.logic.SyncTaskFactory
import com.tortugapower.audiobookplayer.repository.LibraryRepository
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * The ordering brain for the library: resolves a location's [EffectiveSort], performs the sort
 * *action* (rewrite `orderRank`, persist, let the syncing repository emit the metadata-changed
 * events), and applies the state-transition hooks that make a sort "sticky" (§4).
 *
 * A sort is an ACTION that rewrites ranks, never a query-time transform — list queries always order
 * by `orderRank`. Every mutating operation is a silent NO-OP when the location is
 * [SortLocation.Unresolved] (placeholder-uuid folder or bound volume): neither the preference nor
 * the ranks are touched, and the caller re-tries once a real uuid materializes.
 *
 * [repository] MUST be the outermost (syncing) repository so that [LibraryRepository.reorderItems]
 * routes through the sync-suppression path.
 */
class LibrarySortManager(
    private val repository: LibraryRepository,
    private val sortStore: LibrarySortStore,
    private val syncTaskRepository: SyncTaskRepository,
) {

    /** A uuid is "synced" once it has no pending first-time upload — i.e. the server knows it. */
    suspend fun isUuidSynced(uuid: String): Boolean =
        syncTaskRepository.getPendingTaskByTypeAndTaskId(SyncTaskFactory.JOB_UPLOAD_METADATA, uuid) == null

    suspend fun resolveLocation(path: String?): SortLocation =
        SortLocationResolver.resolve(path, repository::getItemByPath, ::isUuidSynced)

    suspend fun effectiveSort(path: String?): EffectiveSort = sortStore.get(resolveLocation(path))

    fun observeEffectiveSort(location: SortLocation): Flow<EffectiveSort> = sortStore.observe(location)

    /**
     * User picked a sort rule. Persist the preference FIRST, then rewrite ranks — ordering matters:
     * the sync-suppression check reads the location's *current* preference, so it must already show
     * `automatic` when the rank rewrite fires (otherwise the re-sort's rank churn would sync). No-op
     * for an unresolved location (both the pref write and the rank rewrite).
     */
    suspend fun applySort(path: String?, sortType: SortType) {
        val location = resolveLocation(path)
        if (!location.isWritable) return
        sortStore.set(location, EffectiveSort.Automatic(sortType))
        rewriteRanks(path, sortType)
    }

    /**
     * A newcomer arrived in this location (import, move-in, or server sync). If the location sorts
     * automatically, re-run the sort so the newcomer lands in rule order; if it's custom, do nothing
     * — the caller already appended the newcomer at the end. No-op for an unresolved location.
     */
    suspend fun resortIfAutomatic(path: String?) {
        val location = resolveLocation(path)
        if (!location.isWritable) return
        val sortType = sortStore.get(location).sortTypeOrNull ?: return
        rewriteRanks(path, sortType)
    }

    /**
     * Manual drag-and-drop reorder: flip the location to custom and persist the moved ranks
     * ([orderedItems] is already in the user's chosen order). No-op for an unresolved location — a
     * bound volume's chapters / a placeholder folder can never be re-ranked.
     */
    suspend fun setCustomOrder(path: String?, orderedItems: List<LibraryItemEntity>) {
        val location = resolveLocation(path)
        if (!location.isWritable) return
        sortStore.set(location, EffectiveSort.Custom)
        repository.reorderItems(orderedItems)
    }

    /**
     * User explicitly chose "Custom": flip the location to manual order, keeping the current ranks as
     * they are (no rewrite). No-op for an unresolved location.
     */
    suspend fun setCustom(path: String?) {
        val location = resolveLocation(path)
        if (!location.isWritable) return
        sortStore.set(location, EffectiveSort.Custom)
    }

    /**
     * "Reverse order": a one-off flip of the current ranks plus a transition to custom — the exact
     * same state change as a manual drag. No-op for an unresolved location.
     */
    suspend fun reverseOrder(path: String?) {
        val location = resolveLocation(path)
        if (!location.isWritable) return
        sortStore.set(location, EffectiveSort.Custom)
        repository.reorderItems(childrenOf(path).reversed())
    }

    /**
     * Apply a sort preference that arrived from a sync pull, addressed by its store key. Resolve the
     * key back to a location, and if it now sorts automatically, re-run the sort locally so ranks
     * converge — without any rank data crossing the wire (the resort's rank churn is suppressed).
     */
    suspend fun resortForStoreKey(key: String) {
        when (val location = SortLocation.fromStoreKey(key)) {
            SortLocation.Root -> resortIfAutomatic(null)
            is SortLocation.Folder -> {
                val path = repository.getItemById(location.uuid)?.relativePath ?: return
                resortIfAutomatic(path)
            }
            else -> return
        }
    }

    private suspend fun rewriteRanks(path: String?, sortType: SortType) {
        val ordered = sortType.sorted(childrenOf(path))
        repository.reorderItems(ordered)
    }

    private suspend fun childrenOf(path: String?): List<LibraryItemEntity> =
        if (path.isNullOrEmpty()) repository.getRootItems().first()
        else repository.getItemsInPathSync(path)
}
