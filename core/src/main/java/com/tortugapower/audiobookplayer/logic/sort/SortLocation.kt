package com.tortugapower.audiobookplayer.logic.sort

/**
 * Where a sort preference lives. A location's effective sort is stored in the shared key-value store
 * keyed by location — there is NO inheritance between root and folders; each is independent.
 *
 * Three resolution outcomes, kept deliberately distinct:
 * - [Root] — the library root, key `library_sort:default`.
 * - [Folder] — a folder with a real, server-assigned uuid, key `library_sort:<uuid>` (uuid, not
 *   path, so the preference survives folder renames).
 * - [Unresolved] — a folder whose uuid is still a local placeholder (not yet synced), or a
 *   bound/merged volume. Reads return [EffectiveSort.Custom]; writes and rank rewrites are silent
 *   NO-OPS. The caller re-tries after a real uuid materializes.
 */
sealed interface SortLocation {

    /** The key-value store key for this location, or null when there is nowhere to store it. */
    val storeKey: String?

    data object Root : SortLocation {
        override val storeKey: String = "$KEY_PREFIX$ROOT_SUFFIX"
    }

    data class Folder(val uuid: String) : SortLocation {
        override val storeKey: String = "$KEY_PREFIX$uuid"
    }

    data object Unresolved : SortLocation {
        override val storeKey: String? = null
    }

    /** True when this location can neither store a preference nor have its ranks rewritten. */
    val isWritable: Boolean get() = storeKey != null

    companion object {
        const val KEY_PREFIX = "library_sort:"
        const val ROOT_SUFFIX = "default"

        /** Reverse of [storeKey]: which location does a `library_sort:*` key belong to? */
        fun fromStoreKey(key: String): SortLocation? {
            if (!key.startsWith(KEY_PREFIX)) return null
            val suffix = key.removePrefix(KEY_PREFIX)
            return if (suffix == ROOT_SUFFIX) Root else Folder(suffix)
        }
    }
}
