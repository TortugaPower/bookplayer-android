package com.tortugapower.audiobookplayer.logic.sort

import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.logic.SyncTaskFactory
import com.tortugapower.audiobookplayer.repository.AccountRepository
import com.tortugapower.audiobookplayer.repository.LibraryRepository
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * The ordering brain for the library. Order is a VIEW transform, not stored state: while a location's
 * sort is `automatic`, `orderRank` is ignored and the displayed order is computed from the rule; only
 * `custom` order reads/writes `orderRank`. This is what keeps the list stable when a background
 * fetch rewrites ranks — an automatic level simply re-derives the same rule order.
 *
 * Picking a rule writes only the preference (no rank rewrite, no rank sync). Manual drag / reverse /
 * explicitly choosing Custom transition the level to `custom`, materializing the currently-visible
 * order into `orderRank` so nothing jumps, and those rank writes DO sync.
 *
 * Every preference change is mirrored to the server via a [SyncTaskFactory.JOB_UPLOAD_PREFERENCE]
 * task on the dedicated preferences queue (gated on a subscribed account). Every mutating op is a
 * silent NO-OP for an [SortLocation.Unresolved] location (placeholder-uuid folder / bound volume).
 *
 * [repository] MUST be the outermost (syncing) repository so [LibraryRepository.reorderItems] routes
 * through the item-sync path.
 */
class LibrarySortManager(
    private val repository: LibraryRepository,
    private val sortStore: LibrarySortStore,
    private val syncTaskRepository: SyncTaskRepository,
    private val accountRepository: AccountRepository,
) {

    /** A uuid is server-confirmed once it has no pending first-time upload. */
    suspend fun isUuidSynced(uuid: String): Boolean =
        syncTaskRepository.getPendingTaskByTypeAndTaskId(SyncTaskFactory.JOB_UPLOAD_METADATA, uuid) == null

    suspend fun resolveLocation(path: String?): SortLocation =
        SortLocationResolver.resolve(path, repository::getItemByPath, ::isUuidSynced)

    suspend fun effectiveSort(path: String?): EffectiveSort = sortStore.get(resolveLocation(path))

    fun observeEffectiveSort(location: SortLocation): Flow<EffectiveSort> = sortStore.observe(location)

    /**
     * Applies [path]'s effective sort to [items] — the one-shot counterpart of the library screen's
     * view transform, for suspend surfaces (Android Auto's browse tree).
     */
    suspend fun sortedForDisplay(path: String?, items: List<LibraryItemEntity>): List<LibraryItemEntity> =
        when (val sort = effectiveSort(path)) {
            is EffectiveSort.Automatic -> sort.sortType.sorted(items)
            EffectiveSort.Custom -> items
        }

    /**
     * Snapshot of every stored `library_sort:*` preference; emits on any change (rule picks,
     * custom flips, remote preference fetches). Android Auto uses it to invalidate cached
     * browse nodes when the sort changes mid-session.
     */
    fun observeSortPreferences(): Flow<Map<String, String>> = sortStore.observeAllPreferences()

    /**
     * User picked an automatic sort rule. Persist the preference only — the list re-derives its order
     * from the rule (no rank rewrite, no rank sync). No-op for an unresolved location.
     */
    suspend fun applySort(path: String?, sortType: SortType) {
        val location = resolveLocation(path)
        if (!location.isWritable) return
        setPreference(location, EffectiveSort.Automatic(sortType))
    }

    /**
     * User explicitly chose "Custom". Freeze the currently-visible order into `orderRank` (so nothing
     * jumps), then flip to custom. No-op for an unresolved location.
     */
    suspend fun setCustom(path: String?) {
        val location = resolveLocation(path)
        if (!location.isWritable) return
        val current = sortStore.get(location)
        if (current is EffectiveSort.Automatic) {
            repository.reorderItems(current.sortType.sorted(childrenOf(path)))
        }
        setPreference(location, EffectiveSort.Custom)
    }

    /**
     * Manual drag-and-drop reorder: flip to custom and persist the moved ranks ([orderedItems] is in
     * the user's chosen order). The rank writes sync as normal item updates. No-op for an unresolved
     * location — a bound volume / placeholder folder can never be re-ranked.
     */
    suspend fun setCustomOrder(path: String?, orderedItems: List<LibraryItemEntity>) {
        val location = resolveLocation(path)
        if (!location.isWritable) return
        setPreference(location, EffectiveSort.Custom)
        repository.reorderItems(orderedItems)
    }

    /**
     * "Reverse order": reverse the currently-visible order into `orderRank` and transition to custom —
     * the same state change as a manual drag. No-op for an unresolved location.
     */
    suspend fun reverseOrder(path: String?) {
        val location = resolveLocation(path)
        if (!location.isWritable) return
        val visible = displayOrder(location, path)
        setPreference(location, EffectiveSort.Custom)
        repository.reorderItems(visible.reversed())
    }

    /** Logout: drop every local sort preference (queued push tasks are cleared with the sync queue). */
    suspend fun clearLocalPreferences() {
        sortStore.removeAll()
    }

    /** The order the user currently sees for [location]: by rule when automatic, by rank when custom. */
    private suspend fun displayOrder(location: SortLocation, path: String?): List<LibraryItemEntity> {
        val children = childrenOf(path) // already orderRank-ordered from the DAO
        return when (val sort = sortStore.get(location)) {
            is EffectiveSort.Automatic -> sort.sortType.sorted(children)
            EffectiveSort.Custom -> children
        }
    }

    /** Write the preference locally and, when subscribed, queue a push to the server. */
    private suspend fun setPreference(location: SortLocation, sort: EffectiveSort) {
        sortStore.set(location, sort)
        val key = location.storeKey ?: return
        if (isSubscribed()) {
            SyncTaskFactory.createUploadPreferenceTask(syncTaskRepository, key, sort.serialize())
        }
    }

    private suspend fun isSubscribed(): Boolean {
        val tier = accountRepository.getAccount()?.tier
        return tier == AccountTier.PRO || tier == AccountTier.LITE
    }

    private suspend fun childrenOf(path: String?): List<LibraryItemEntity> =
        if (path.isNullOrEmpty()) repository.getRootItems().first()
        else repository.getItemsInPathSync(path)
}
