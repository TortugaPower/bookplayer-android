package com.tortugapower.audiobookplayer.logic

import android.content.Context
import com.tortugapower.audiobookplayer.network.throwIfCoded
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.logic.preferences.DataStorePreferencesStore
import com.tortugapower.audiobookplayer.logic.preferences.PreferenceChanges
import com.tortugapower.audiobookplayer.logic.preferences.PreferenceFamily
import com.tortugapower.audiobookplayer.logic.preferences.PreferencesStore
import com.tortugapower.audiobookplayer.network.NetworkClient

/**
 * PATCH body for one preference entry, the wire contract shared with iOS
 * (`{"entries":[{"key":…,"value":{…}}]}`), the value in [key]'s family's shape: null for a key no family owns.
 * Internal so the shape is pinned by a unit test.
 */
internal fun buildPreferencePushBody(key: String, value: String): Map<String, Any>? {
    val family = PreferenceFamily.of(key) ?: return null
    return mapOf("entries" to listOf(mapOf("key" to key, "value" to family.encode(value))))
}

/**
 * Pushes one preference (a level's sort rule, the layout, a display toggle) to the server via
 * `PATCH /v1/user/preferences {entries:[{key,value}]}` (iOS parity). The local key-value store is
 * the source of truth; this task mirrors a single changed key upward. Taken, it clears the change it carried
 * ([PreferenceChanges]), not one made since.
 */
class PreferenceUploadProcessor(
    // Injectable so tests can point at a MockWebServer-backed API; production uses the singleton.
    private val api: com.tortugapower.audiobookplayer.network.PreferencesApi = NetworkClient.preferencesApi,
    private val changes: PreferenceChanges = PreferenceChanges(DataStorePreferencesStore()),
) : TaskProcessor {
    private val gson = Gson()

    override fun canHandle(jobType: String): Boolean = jobType == SyncTaskFactory.JOB_UPLOAD_PREFERENCE

    override suspend fun process(task: SyncTaskEntity): Boolean {
        val payloadType = object : TypeToken<Map<String, Any?>>() {}.type
        val payload: Map<String, Any?> = gson.fromJson(task.payload, payloadType)
        val key = payload["key"] as? String ?: return true // malformed → drop
        val value = payload["value"] as? String ?: return true
        val body = buildPreferencePushBody(key, value) ?: return true // no family owns it → drop
        val response = api.setPreferences(body)
        response.throwIfCoded()
        if (!response.isSuccessful) return false
        // Gson reads the number as a double; a task queued before changes were recorded has none
        (payload["changedAt"] as? Number)?.let { changes.clear(key, it.toLong()) }
        return true
    }
}

/**
 * Pulls the user's preferences of [families] (`GET /v1/user/preferences`) and writes the valid values into the
 * local store; the library follows the store reactively. One request without a prefix fetches every family, as iOS
 * does; a single family asks for its prefix alone (the watch syncs sort only). Entries no family owns, and invalid
 * values, are skipped.
 *
 * A conflict goes to the newer change, as on iOS: a key changed here ([PreferenceChanges]) keeps its value unless the
 * server's `updated_at` is later, which then replaces it and drops its queued upload. Then every change the server
 * still lacks (made while FREE, or whose upload a lapse wiped) is queued for upload.
 */
class PreferenceFetchProcessor(
    private val context: Context,
    private val syncTaskRepository: com.tortugapower.audiobookplayer.repository.SyncTaskRepository,
    // Injectable so tests can point at a MockWebServer-backed API; production uses the singleton.
    private val api: com.tortugapower.audiobookplayer.network.PreferencesApi = NetworkClient.preferencesApi,
    private val families: List<PreferenceFamily> = PreferenceFamily.ALL,
    private val store: PreferencesStore = DataStorePreferencesStore(context),
    private val changes: PreferenceChanges = PreferenceChanges(store),
) : TaskProcessor {

    override fun canHandle(jobType: String): Boolean = jobType == SyncTaskFactory.JOB_FETCH_PREFERENCES

    override suspend fun process(task: SyncTaskEntity): Boolean {
        val response = api.getPreferences(prefix = families.singleOrNull()?.prefix)
        response.throwIfCoded()
        if (!response.isSuccessful) return false
        val entries = response.body()?.entries ?: emptyList()
        entries.forEach { entry ->
            val family = PreferenceFamily.of(entry.key, families) ?: return@forEach
            val changedAt = changes.changedAt(entry.key)
            if (changedAt != null) {
                // Changed here: the server's value wins only when it's newer. Without a time, ours does.
                val serverAt = entry.updatedAtMillis()
                if (serverAt == null || serverAt <= changedAt) return@forEach
            } else if (syncTaskRepository.hasQueuedTask(SyncTaskFactory.JOB_UPLOAD_PREFERENCE, entry.key)) {
                // An upload queued by an older build, with no recorded change: its value is the newer one
                return@forEach
            }
            val value = family.decode(entry.value)
            if (value == null) {
                Log.w("PreferenceFetch", "Rejecting an invalid pulled ${entry.key}")
                return@forEach
            }
            store.setString(entry.key, value)
            if (changedAt != null) {
                // Superseded: the older local value mustn't go up after it. Only the change read above: one made
                // during the pull is newer than the server's, and keeps its upload.
                changes.clear(entry.key, changedAt)
                if (changes.changedAt(entry.key) == null) {
                    syncTaskRepository.deleteParkedTasks(SyncTaskFactory.JOB_UPLOAD_PREFERENCE, entry.key)
                    syncTaskRepository.getPendingTaskByTypeAndTaskId(SyncTaskFactory.JOB_UPLOAD_PREFERENCE, entry.key)
                        ?.let { syncTaskRepository.deleteTask(it) }
                }
            }
        }
        uploadUnsentChanges()
        return true
    }

    /** Queues every change of [families] the server hasn't taken and no upload carries (iOS `bootstrap`'s flush). */
    private suspend fun uploadUnsentChanges() {
        changes.all().forEach { (key, changedAt) ->
            val family = PreferenceFamily.of(key, families) ?: return@forEach
            if (syncTaskRepository.hasQueuedTask(SyncTaskFactory.JOB_UPLOAD_PREFERENCE, key)) return@forEach
            val value = store.getString(key)
            if (value == null || family.decode(family.encode(value)) == null) {
                // Nothing valid to send (reset since): nothing owed
                changes.clear(key, changedAt)
                return@forEach
            }
            SyncTaskFactory.createUploadPreferenceTask(syncTaskRepository, key, value, changedAt)
        }
    }
}
