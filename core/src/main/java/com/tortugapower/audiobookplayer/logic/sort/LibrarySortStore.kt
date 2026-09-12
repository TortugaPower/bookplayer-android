package com.tortugapower.audiobookplayer.logic.sort

import com.tortugapower.audiobookplayer.logic.preferences.PreferencesStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Reads and writes a location's [EffectiveSort] in the shared key-value store, keyed by
 * [SortLocation.storeKey].
 *
 * An [SortLocation.Unresolved] location reads as [EffectiveSort.Custom] and its writes are silent
 * no-ops — you can never leave a partial preference against a placeholder-uuid key or a bound volume.
 */
class LibrarySortStore(private val prefs: PreferencesStore) {

    suspend fun get(location: SortLocation): EffectiveSort {
        val key = location.storeKey ?: return EffectiveSort.Custom
        return EffectiveSort.deserialize(prefs.getString(key))
    }

    /** No-op when [location] is unresolved (no store key). */
    suspend fun set(location: SortLocation, sort: EffectiveSort) {
        val key = location.storeKey ?: return
        prefs.setString(key, sort.serialize())
    }

    fun observe(location: SortLocation): Flow<EffectiveSort> {
        val key = location.storeKey
            ?: return kotlinx.coroutines.flow.flowOf(EffectiveSort.Custom)
        return prefs.observeString(key).map { EffectiveSort.deserialize(it) }
    }

    /** Snapshot of every stored `library_sort:*` entry; emits on any change to the store. */
    fun observeAllPreferences(): Flow<Map<String, String>> =
        prefs.observeAll().map { all -> all.filterKeys { it.startsWith(SortLocation.KEY_PREFIX) } }

    /** Remove every stored `library_sort:*` preference (used on logout). */
    suspend fun removeAll() {
        prefs.removeWithPrefix(SortLocation.KEY_PREFIX)
    }
}
