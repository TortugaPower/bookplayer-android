package com.tortugapower.audiobookplayer.logic

import android.content.Context
import com.tortugapower.audiobookplayer.network.throwIfCoded
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.logic.preferences.DataStorePreferencesStore
import com.tortugapower.audiobookplayer.logic.preferences.PreferenceFamily
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
 * the source of truth; this task mirrors a single changed key upward.
 */
class PreferenceUploadProcessor(
    // Injectable so tests can point at a MockWebServer-backed API; production uses the singleton.
    private val api: com.tortugapower.audiobookplayer.network.PreferencesApi = NetworkClient.preferencesApi,
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
        return response.isSuccessful
    }
}

/**
 * Pulls the user's preferences of [families] (`GET /v1/user/preferences`) and writes the valid values into the
 * local store; the library follows the store reactively. One request without a prefix fetches every family, as iOS
 * does; a single family asks for its prefix alone (the watch syncs sort only). Entries no family owns, and invalid
 * values, are skipped.
 */
class PreferenceFetchProcessor(
    private val context: Context,
    private val syncTaskRepository: com.tortugapower.audiobookplayer.repository.SyncTaskRepository,
    // Injectable so tests can point at a MockWebServer-backed API; production uses the singleton.
    private val api: com.tortugapower.audiobookplayer.network.PreferencesApi = NetworkClient.preferencesApi,
    private val families: List<PreferenceFamily> = PreferenceFamily.ALL,
) : TaskProcessor {

    override fun canHandle(jobType: String): Boolean = jobType == SyncTaskFactory.JOB_FETCH_PREFERENCES

    override suspend fun process(task: SyncTaskEntity): Boolean {
        val response = api.getPreferences(prefix = families.singleOrNull()?.prefix)
        response.throwIfCoded()
        if (!response.isSuccessful) return false
        val entries = response.body()?.entries ?: emptyList()
        val store = DataStorePreferencesStore(context)
        entries.forEach { entry ->
            val family = PreferenceFamily.of(entry.key, families) ?: return@forEach
            // A key with a queued upload (parked ones included) has a LOCAL value newer than the
            // server's — writing the pulled value would revert the user's just-made choice until the
            // next pull (the enqueue-time guard can't help once this fetch is already sitting in the queue).
            if (syncTaskRepository.hasQueuedTask(SyncTaskFactory.JOB_UPLOAD_PREFERENCE, entry.key)) {
                return@forEach
            }
            val value = family.decode(entry.value)
            if (value == null) {
                Log.w("PreferenceFetch", "Rejecting an invalid pulled ${entry.key}")
                return@forEach
            }
            store.setString(entry.key, value)
        }
        return true
    }
}
