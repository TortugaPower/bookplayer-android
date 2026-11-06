package com.tortugapower.audiobookplayer.logic.preferences

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * The synced preferences changed here that the server hasn't taken yet, each with when it changed (iOS
 * `userPreferences.dirty`). Every change is recorded, whatever the account: the server's copy decides a conflict by
 * time (a pulled value replaces a local change only when the server's is newer), and a change it never got (made
 * while FREE, or whose upload a lapse wiped) is uploaded after the next pull. An upload the server took clears it.
 * Kept in the same store as the values, so sign-out clears it with them.
 */
class PreferenceChanges(
    private val store: PreferencesStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Records [key] as changed now and returns when. */
    suspend fun mark(key: String): Long {
        val at = clock()
        edit { it[key] = at }
        return at
    }

    /** When [key] last changed here without the server taking it, or null. */
    suspend fun changedAt(key: String): Long? = read()[key]

    suspend fun all(): Map<String, Long> = read()

    /** The server took [key]'s change of [at] (or has a newer value, when [at] is null). A later change stays. */
    suspend fun clear(key: String, at: Long? = null) {
        edit { if (at == null || it[key] == at) it.remove(key) }
    }

    suspend fun clearAll() {
        store.remove(KEY)
    }

    private suspend fun read(): Map<String, Long> = decode(store.getString(KEY))

    private suspend fun edit(change: (MutableMap<String, Long>) -> Unit) {
        store.update(KEY) { current ->
            val changes = decode(current).toMutableMap().also(change)
            if (changes.isEmpty()) null else gson.toJson(changes)
        }
    }

    private fun decode(json: String?): Map<String, Long> {
        if (json.isNullOrEmpty()) return emptyMap()
        val raw: Map<String, Number>? = runCatching { gson.fromJson<Map<String, Number>>(json, type) }.getOrNull()
        return raw.orEmpty().mapValues { it.value.toLong() }
    }

    companion object {
        /** iOS's key. */
        const val KEY = "userPreferences.dirty"

        private val gson = Gson()
        private val type = object : TypeToken<Map<String, Number>>() {}.type
    }
}
