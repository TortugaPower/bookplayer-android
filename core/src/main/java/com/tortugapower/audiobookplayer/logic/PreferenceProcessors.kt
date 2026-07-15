package com.tortugapower.audiobookplayer.logic

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.logic.preferences.DataStorePreferencesStore
import com.tortugapower.audiobookplayer.logic.sort.EffectiveSort
import com.tortugapower.audiobookplayer.logic.sort.SortLocation
import com.tortugapower.audiobookplayer.network.NetworkClient

/**
 * A preference value on the server is a JSON object ([String: Any]); a library-sort entry stores its
 * serialized rule under this field. Keeping it in one place ties the push and pull shapes together.
 */
// The inner field name of the preference value object. iOS reads/writes {"sort": "<rawValue>"}
// (PreferencesSyncService encode/decode) — this MUST stay "sort" or cross-platform preference
// sync silently breaks in both directions: each side drops the other's entries as invalid.
private const val SORT_VALUE_FIELD = "sort"

/**
 * PATCH body for one preference entry — the wire contract shared with iOS
 * (`{"entries":[{"key":…,"value":{"sort":…}}]}`). Internal so the shape is pinned by a unit test.
 */
internal fun buildPreferencePushBody(key: String, value: String): Map<String, Any> =
    mapOf("entries" to listOf(mapOf("key" to key, "value" to mapOf(SORT_VALUE_FIELD to value))))

/** Extracts the sort raw value from a pulled entry's value object — iOS writes it under "sort". */
internal fun parsePulledSortValue(value: Map<String, Any?>?): String? =
    value?.get(SORT_VALUE_FIELD) as? String

/**
 * Pushes one preference level (root or a folder's sort rule) to the server via
 * `PATCH /v1/user/preferences {entries:[{key,value}]}` (iOS parity). The local key-value store is
 * the source of truth; this task mirrors a single changed key upward.
 */
class PreferenceUploadProcessor : TaskProcessor {
    private val gson = Gson()

    override fun canHandle(jobType: String): Boolean = jobType == SyncTaskFactory.JOB_UPLOAD_PREFERENCE

    override suspend fun process(task: SyncTaskEntity): Boolean {
        val payloadType = object : TypeToken<Map<String, Any?>>() {}.type
        val payload: Map<String, Any?> = gson.fromJson(task.payload, payloadType)
        val key = payload["key"] as? String ?: return true // malformed → drop
        val value = payload["value"] as? String ?: return true
        val response = NetworkClient.preferencesApi.setPreferences(buildPreferencePushBody(key, value))
        return response.isSuccessful
    }
}

/**
 * Pulls the user's sort preferences (`GET /v1/user/preferences?prefix=library_sort:`) and writes
 * valid values into the local store. The library UI derives its order from these reactively, so no
 * explicit re-sort is needed. Unknown/invalid sort strings are rejected.
 */
class PreferenceFetchProcessor(
    private val context: Context,
    private val syncTaskRepository: com.tortugapower.audiobookplayer.repository.SyncTaskRepository,
) : TaskProcessor {

    override fun canHandle(jobType: String): Boolean = jobType == SyncTaskFactory.JOB_FETCH_PREFERENCES

    override suspend fun process(task: SyncTaskEntity): Boolean {
        val response = NetworkClient.preferencesApi.getPreferences(prefix = SortLocation.KEY_PREFIX)
        if (!response.isSuccessful) return false
        val entries = response.body()?.entries ?: emptyList()
        val store = DataStorePreferencesStore(context)
        entries.forEach { entry ->
            if (!entry.key.startsWith(SortLocation.KEY_PREFIX)) return@forEach
            // A key with a queued upload has a LOCAL value newer than the server's — writing the
            // pulled value would revert the user's just-made choice until the next pull (the
            // enqueue-time guard can't help once this fetch is already sitting in the queue).
            if (syncTaskRepository.getPendingTaskByTypeAndTaskId(SyncTaskFactory.JOB_UPLOAD_PREFERENCE, entry.key) != null) {
                return@forEach
            }
            val value = parsePulledSortValue(entry.value) ?: return@forEach
            if (!EffectiveSort.isValidRawValue(value)) {
                Log.w("PreferenceFetch", "Rejecting invalid pulled sort ${entry.key}=$value")
                return@forEach
            }
            store.setString(entry.key, value)
        }
        return true
    }
}
