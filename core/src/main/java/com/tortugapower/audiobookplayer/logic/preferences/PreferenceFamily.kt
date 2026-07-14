package com.tortugapower.audiobookplayer.logic.preferences

import com.tortugapower.audiobookplayer.logic.sort.EffectiveSort
import com.tortugapower.audiobookplayer.logic.sort.LibrarySortManager
import com.tortugapower.audiobookplayer.logic.sort.SortLocation

/**
 * A group of related preference keys that sync as a unit. Each family declares which keys it owns
 * (by [keyPrefix]), how to [isValid]ate an incoming value from a sync pull, and the [onApplied] side
 * effect to run after a pulled value is written to the local store (sort = resort that location's
 * ranks; display prefs = none).
 */
interface PreferenceFamily {
    val keyPrefix: String
    fun matches(key: String): Boolean = key.startsWith(keyPrefix)
    fun isValid(key: String, value: String): Boolean
    suspend fun onApplied(key: String, value: String)
}

/**
 * The `library_sort:*` family. Values are either a known [com.tortugapower.audiobookplayer.logic.sort.SortType]
 * raw name or `"custom"`; anything else from the server is rejected. Applying a pulled value re-sorts
 * that location's ranks locally.
 */
class LibrarySortPreferenceFamily(
    private val sortManager: LibrarySortManager
) : PreferenceFamily {
    override val keyPrefix: String = SortLocation.KEY_PREFIX

    override fun isValid(key: String, value: String): Boolean = EffectiveSort.isValidRawValue(value)

    override suspend fun onApplied(key: String, value: String) {
        sortManager.resortForStoreKey(key)
    }
}
