package com.tortugapower.audiobookplayer.logic

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import java.io.IOException

/**
 * What the library sync remembers across launches (iOS keeps the same in UserDefaults). Its own file, kept
 * out of backups: a restored install must sync like a fresh one. Lost or unreadable, it reads as nothing
 * done yet, which only runs one more first sync (and that never deletes).
 */
interface SyncStateStore {
    /** Whether this account's library has been through its first sync on this device */
    suspend fun hasRunFirstSync(): Boolean
    suspend fun setHasRunFirstSync(done: Boolean)

    /** When the missing-items pass last ran (epoch ms), 0 never: it runs again a week later */
    suspend fun passLastRun(): Long
    suspend fun setPassLastRun(at: Long)

    /** A pass owed since the account gained PRO: it uploads the files LITE never sent */
    suspend fun isPassPending(): Boolean
    suspend fun setPassPending(pending: Boolean)

    /** Whether the account had PRO when last seen; null before its first reading */
    suspend fun lastKnownProAccess(): Boolean?
    suspend fun setLastKnownProAccess(pro: Boolean)

    /** Forgets everything: sign-out */
    suspend fun clear()
}

private val Context.syncStateDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "sync_state",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

class DataStoreSyncStateStore(private val dataStore: DataStore<Preferences>) : SyncStateStore {
    constructor(context: Context) : this(context.applicationContext.syncStateDataStore)

    private suspend fun read(): Preferences = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .first()

    override suspend fun hasRunFirstSync(): Boolean = read()[HAS_RUN_FIRST_SYNC] ?: false

    override suspend fun setHasRunFirstSync(done: Boolean) {
        dataStore.edit { it[HAS_RUN_FIRST_SYNC] = done }
    }

    override suspend fun passLastRun(): Long = read()[PASS_LAST_RUN] ?: 0L

    override suspend fun setPassLastRun(at: Long) {
        dataStore.edit { it[PASS_LAST_RUN] = at }
    }

    override suspend fun isPassPending(): Boolean = read()[PASS_PENDING] ?: false

    override suspend fun setPassPending(pending: Boolean) {
        dataStore.edit { it[PASS_PENDING] = pending }
    }

    override suspend fun lastKnownProAccess(): Boolean? = read()[LAST_KNOWN_PRO_ACCESS]

    override suspend fun setLastKnownProAccess(pro: Boolean) {
        dataStore.edit { it[LAST_KNOWN_PRO_ACCESS] = pro }
    }

    override suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    private companion object {
        val HAS_RUN_FIRST_SYNC = booleanPreferencesKey("has_run_first_sync")
        val PASS_LAST_RUN = longPreferencesKey("missing_items_pass_last_run")
        val PASS_PENDING = booleanPreferencesKey("missing_items_pass_pending")
        val LAST_KNOWN_PRO_ACCESS = booleanPreferencesKey("last_known_pro_access")
    }
}
