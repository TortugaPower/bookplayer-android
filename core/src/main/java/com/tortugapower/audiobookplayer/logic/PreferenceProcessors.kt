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
private const val SORT_VALUE_FIELD = "value"

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
        val body = mapOf(
            "entries" to listOf(
                mapOf("key" to key, "value" to mapOf(SORT_VALUE_FIELD to value))
            )
        )
        val response = NetworkClient.preferencesApi.setPreferences(body)
        return response.isSuccessful
    }
}

/**
 * Pulls the user's sort preferences (`GET /v1/user/preferences?prefix=library_sort:`) and writes
 * valid values into the local store. The library UI derives its order from these reactively, so no
 * explicit re-sort is needed. Unknown/invalid sort strings are rejected.
 */
class PreferenceFetchProcessor(
    private val context: Context
) : TaskProcessor {

    override fun canHandle(jobType: String): Boolean = jobType == SyncTaskFactory.JOB_FETCH_PREFERENCES

    override suspend fun process(task: SyncTaskEntity): Boolean {
        val response = NetworkClient.preferencesApi.getPreferences(prefix = SortLocation.KEY_PREFIX)
        if (!response.isSuccessful) return false
        val entries = response.body()?.entries ?: emptyList()
        val store = DataStorePreferencesStore(context)
        entries.forEach { entry ->
            if (!entry.key.startsWith(SortLocation.KEY_PREFIX)) return@forEach
            val value = entry.value?.get(SORT_VALUE_FIELD) as? String ?: return@forEach
            if (!EffectiveSort.isValidRawValue(value)) {
                Log.w("PreferenceFetch", "Rejecting invalid pulled sort ${entry.key}=$value")
                return@forEach
            }
            store.setString(entry.key, value)
        }
        return true
    }
}
