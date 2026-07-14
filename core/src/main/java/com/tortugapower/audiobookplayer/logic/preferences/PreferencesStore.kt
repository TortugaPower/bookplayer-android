package com.tortugapower.audiobookplayer.logic.preferences

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.tortugapower.audiobookplayer.core.CoreContext
import com.tortugapower.audiobookplayer.logic.dataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * A dynamic, string-keyed view over the app's shared key-value store. The existing
 * [com.tortugapower.audiobookplayer.logic.PlaybackSettingsManager] uses fixed, compile-time keys;
 * the sticky-sort feature needs keys built at runtime (`library_sort:<uuid>`), so this exposes
 * generic string get/set/remove/observe plus prefix enumeration (for logout wipe and change
 * detection).
 *
 * An interface so tests can substitute an in-memory fake without Android's DataStore.
 */
interface PreferencesStore {
    suspend fun getString(key: String): String?
    suspend fun setString(key: String, value: String)
    suspend fun remove(key: String)
    /** Every currently-set key whose name starts with [prefix]. */
    suspend fun keysWithPrefix(prefix: String): Set<String>
    /** Remove every currently-set key whose name starts with [prefix]. */
    suspend fun removeWithPrefix(prefix: String)
    /** Observe a single key; emits its current value and on every subsequent change. */
    fun observeString(key: String): Flow<String?>
    /** Observe the whole store; emits a snapshot map of key-name -> value on every change. */
    fun observeAll(): Flow<Map<String, String>>
}

/**
 * [PreferencesStore] backed by the shared `playback_settings` DataStore (the same store
 * [com.tortugapower.audiobookplayer.logic.PlaybackSettingsManager] and `HardcoverSettingsManager`
 * use). Only string values are exposed here — sort prefs are strings; other families serialize to
 * strings at this boundary.
 */
class DataStorePreferencesStore(
    providedContext: Context? = null
) : PreferencesStore {

    // Resolved lazily so merely CONSTRUCTING this (e.g. as a default constructor argument on the
    // syncing repository) never touches CoreContext — only actual store access does.
    private val context: Context by lazy { providedContext ?: CoreContext.appContext }

    override suspend fun getString(key: String): String? =
        context.dataStore.data.map { it[stringPreferencesKey(key)] }.first()

    override suspend fun setString(key: String, value: String) {
        context.dataStore.edit { it[stringPreferencesKey(key)] = value }
    }

    override suspend fun remove(key: String) {
        context.dataStore.edit { it.remove(stringPreferencesKey(key)) }
    }

    override suspend fun keysWithPrefix(prefix: String): Set<String> =
        context.dataStore.data.first().asMap().keys
            .map { it.name }
            .filter { it.startsWith(prefix) }
            .toSet()

    override suspend fun removeWithPrefix(prefix: String) {
        context.dataStore.edit { prefs ->
            prefs.asMap().keys
                .filter { it.name.startsWith(prefix) }
                .forEach { prefs.remove(it) }
        }
    }

    override fun observeString(key: String): Flow<String?> =
        context.dataStore.data.map { it[stringPreferencesKey(key)] }

    override fun observeAll(): Flow<Map<String, String>> =
        context.dataStore.data.map { prefs -> prefs.stringSnapshot() }

    private fun Preferences.stringSnapshot(): Map<String, String> =
        asMap().entries
            .mapNotNull { (k, v) -> (v as? String)?.let { k.name to it } }
            .toMap()
}
