package com.tortugapower.audiobookplayer.logic.sort

import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity

/**
 * The three library ordering rules, matching BookPlayer iOS `SortType`. The [rawValue] is the
 * stable string persisted in the key-value store and synced to the backend — it is the enum name,
 * so serialization stays trivial while still going through a single validated parse ([fromRaw]).
 */
enum class SortType(val rawValue: String) {
    /** Locale-aware, numeric-friendly compare on the display title, ascending. */
    metadataTitle("metadataTitle"),

    /** Locale-aware, numeric-friendly compare on the original file name, ascending. */
    fileName("fileName"),

    /**
     * Most-recently-played first (last-played date DESCENDING). Items that were never played have
     * no date and sort as the oldest (treated as the distant past → last).
     */
    mostRecent("mostRecent");

    /**
     * The comparator that this rule imposes on a location's children. Applying a sort re-numbers
     * `orderRank` in the order produced here.
     */
    fun comparator(): Comparator<LibraryItemEntity> = when (this) {
        metadataTitle -> Comparator { a, b -> NaturalOrder.compare(a.title, b.title) }
        fileName -> Comparator { a, b ->
            NaturalOrder.compare(a.originalFileName ?: "", b.originalFileName ?: "")
        }
        // Missing date => distant past => oldest. Descending on date puts newest first; a null date
        // uses Long.MIN_VALUE so never-played items land last regardless of the others' timestamps.
        mostRecent -> compareByDescending { it.lastPlayDate ?: Long.MIN_VALUE }
    }

    /** Order [items] by this rule without mutating them. */
    fun sorted(items: List<LibraryItemEntity>): List<LibraryItemEntity> = items.sortedWith(comparator())

    companion object {
        /** Parse a persisted/synced raw string, or null if it is not a known sort rule. */
        fun fromRaw(raw: String?): SortType? = entries.firstOrNull { it.rawValue == raw }
    }
}
