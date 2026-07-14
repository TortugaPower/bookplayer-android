package com.tortugapower.audiobookplayer.logic.preferences

import com.tortugapower.audiobookplayer.network.NetworkClient
import com.tortugapower.audiobookplayer.network.PreferencesApi

/**
 * The remote side of preference sync, abstracted so [UserPreferencesSyncService] can be unit-tested
 * against an in-memory fake with no network.
 */
interface PreferencesBackend {
    /** All of the user's preferences, values normalized to canonical strings. */
    suspend fun pull(): Map<String, String>
    /** Upsert the given changed preferences. Throws on transport failure so the caller can retry. */
    suspend fun push(values: Map<String, String>)
}

/** [PreferencesBackend] talking to the real BookPlayer API. */
class RetrofitPreferencesBackend(
    private val api: PreferencesApi = NetworkClient.preferencesApi
) : PreferencesBackend {

    override suspend fun pull(): Map<String, String> {
        val response = api.getPreferences()
        if (!response.isSuccessful) {
            throw java.io.IOException("Preferences pull failed: HTTP ${response.code()}")
        }
        val raw = response.body()?.preferences ?: return emptyMap()
        // Normalize every scalar (booleans may arrive as 0/1) and drop nulls.
        return raw.mapNotNull { (key, value) ->
            PreferenceValueParsers.normalizeScalar(value)?.let { key to it }
        }.toMap()
    }

    override suspend fun push(values: Map<String, String>) {
        if (values.isEmpty()) return
        val response = api.putPreferences(mapOf("preferences" to values))
        if (!response.isSuccessful) {
            throw java.io.IOException("Preferences push failed: HTTP ${response.code()}")
        }
    }
}
