package com.tortugapower.audiobookplayer.logic.sort

/**
 * A location's "sticky" sort state — either an [Automatic] rule that re-runs itself as items come
 * and go, or [Custom] manual order (respect `orderRank`, do nothing automatically).
 *
 * Serialized as a single string: the sort rule's [SortType.rawValue] for [Automatic], or
 * [CUSTOM_RAW] for [Custom]. An unset key deserializes to [Custom] (no inheritance, no default rule).
 */
sealed interface EffectiveSort {
    data class Automatic(val sortType: SortType) : EffectiveSort
    data object Custom : EffectiveSort

    fun serialize(): String = when (this) {
        is Automatic -> sortType.rawValue
        Custom -> CUSTOM_RAW
    }

    val sortTypeOrNull: SortType?
        get() = (this as? Automatic)?.sortType

    companion object {
        const val CUSTOM_RAW = "custom"

        /**
         * Read a stored value. Null (unset) and [CUSTOM_RAW] both mean [Custom]. An unknown /
         * corrupt string is treated as [Custom] rather than throwing — a defensive read. Incoming
         * SYNC values are validated separately ([isValidRawValue]) and rejected before they are
         * ever written, so a stored value being unparseable only happens on local corruption.
         */
        fun deserialize(raw: String?): EffectiveSort {
            if (raw == null || raw == CUSTOM_RAW) return Custom
            return SortType.fromRaw(raw)?.let { Automatic(it) } ?: Custom
        }

        /** Whether [raw] is a value we accept from a sync pull (a known rule, or "custom"). */
        fun isValidRawValue(raw: String?): Boolean =
            raw == CUSTOM_RAW || SortType.fromRaw(raw) != null
    }
}
