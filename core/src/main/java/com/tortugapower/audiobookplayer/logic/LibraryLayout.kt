package com.tortugapower.audiobookplayer.logic

/**
 * How the Library tab lays out a folder's items (iOS `LibraryLayout`). [rawValue] is iOS's, so the
 * preference can join iOS's cross-device sync (`library_layout:style`) unchanged.
 */
enum class LibraryLayout(val rawValue: String) {
    /** Rows: artwork beside the title, author and duration. */
    LIST("list"),
    /** Cards: the cover, then the duration, title and author. */
    GRID("grid"),
    /** Covers only, more to a row. */
    COVERS("covers");

    companion object {
        /** The layout stored under [rawValue]; the list for anything unknown or unset. */
        fun fromRawValue(rawValue: String?): LibraryLayout = entries.firstOrNull { it.rawValue == rawValue } ?: LIST
    }
}
