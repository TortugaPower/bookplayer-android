package com.tortugapower.audiobookplayer.logic.preferences

/**
 * Normalizes and validates raw preference values coming off the wire. JSON serializers on the
 * various clients collapse booleans to 0/1 integers, so a boolean-typed preference must accept
 * `true/false` AND `1/0`.
 */
object PreferenceValueParsers {

    /** Parse a synced boolean, tolerating `true/false`, `1/0`. Null when the value isn't a boolean. */
    fun parseBoolean(raw: String): Boolean? = when (raw.trim().lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }

    /**
     * Normalize a raw JSON scalar (as decoded by Gson into String/Boolean/Number) into the canonical
     * String we store. Booleans become "true"/"false"; whole numbers drop the trailing ".0" that Gson
     * gives every JSON number; everything else is `toString()`-ed. Null values are dropped by the caller.
     */
    fun normalizeScalar(value: Any?): String? = when (value) {
        null -> null
        is Boolean -> value.toString()
        is Number -> {
            val d = value.toDouble()
            if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
        }
        else -> value.toString()
    }
}

/** A boolean-valued preference family (e.g. display prefs). Side effect is a no-op by default. */
open class BooleanPreferenceFamily(
    override val keyPrefix: String
) : PreferenceFamily {
    override fun isValid(key: String, value: String): Boolean =
        PreferenceValueParsers.parseBoolean(value) != null

    override suspend fun onApplied(key: String, value: String) {
        // Display prefs declare no side effect — the value is simply stored locally.
    }
}
