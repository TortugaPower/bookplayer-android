package com.tortugapower.audiobookplayer.logic.preferences

import com.tortugapower.audiobookplayer.logic.LibraryLayout
import com.tortugapower.audiobookplayer.logic.sort.EffectiveSort
import com.tortugapower.audiobookplayer.logic.sort.SortLocation

/**
 * A family of synced preferences (iOS `PreferenceSchema`): the keys it owns and the shape of their value on the
 * server, `PATCH`/`GET /v1/user/preferences`. Locally every value is a string in the preferences store; [encode]
 * turns it into the server's value object and [decode] turns a pulled one back, or null when it isn't valid.
 * The shapes MUST stay iOS's, or each side drops the other's entries.
 */
sealed class PreferenceFamily(val prefix: String) {

    /** Whether [key] is one of this family's. */
    open fun owns(key: String): Boolean = key.startsWith(prefix)

    abstract fun encode(value: String): Map<String, Any?>

    abstract fun decode(value: Map<String, Any?>?): String?

    /** A level's sort rule, `library_sort:default` or `library_sort:<uuid>`: `{"sort": "<rawValue>"}`. */
    data object Sort : PreferenceFamily(SortLocation.KEY_PREFIX) {
        override fun encode(value: String): Map<String, Any?> = mapOf("sort" to value)
        override fun decode(value: Map<String, Any?>?): String? =
            (value?.get("sort") as? String)?.takeIf { EffectiveSort.isValidRawValue(it) }
    }

    /** The library's layout: `{"layout": "<LibraryLayout.rawValue>"}`, a known one only. */
    data object Layout : PreferenceFamily("library_layout:") {
        const val KEY = "library_layout:style"

        override fun owns(key: String): Boolean = key == KEY
        override fun encode(value: String): Map<String, Any?> = mapOf("layout" to value)
        override fun decode(value: Map<String, Any?>?): String? =
            (value?.get("layout") as? String)?.takeIf { raw -> LibraryLayout.entries.any { it.rawValue == raw } }
    }

    /**
     * The library's display toggles: `{"value": Bool}`, stored here as "true"/"false". A pulled 0/1 counts too,
     * as iOS reads it (JSON serializers can turn booleans into numbers).
     */
    data object Display : PreferenceFamily("library_display:") {
        /** Show progress as a percentage (iOS: false = wheel, true = percentage). */
        const val PROGRESS_STYLE = "library_display:progress_style"

        /** Show the original file name (iOS: false = parsed title, true = file name). */
        const val TITLE_SOURCE = "library_display:title_source"

        override fun owns(key: String): Boolean = key == PROGRESS_STYLE || key == TITLE_SOURCE
        override fun encode(value: String): Map<String, Any?> = mapOf("value" to (value == true.toString()))
        override fun decode(value: Map<String, Any?>?): String? = when (val raw = value?.get("value")) {
            is Boolean -> raw.toString()
            // Gson reads JSON numbers as doubles
            is Number -> when (raw.toDouble()) {
                0.0 -> false.toString()
                1.0 -> true.toString()
                else -> null
            }
            else -> null
        }
    }

    companion object {
        /** Every family this app syncs (the phone's; the watch keeps [Sort] alone). */
        val ALL: List<PreferenceFamily> = listOf(Sort, Layout, Display)

        /** The family of [key] among [families], or null when none owns it. */
        fun of(key: String, families: List<PreferenceFamily> = ALL): PreferenceFamily? =
            families.firstOrNull { it.owns(key) }
    }
}
