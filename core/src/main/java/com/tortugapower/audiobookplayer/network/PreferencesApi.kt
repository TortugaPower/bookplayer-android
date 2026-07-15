package com.tortugapower.audiobookplayer.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.PATCH
import retrofit2.http.Query

/**
 * User-preferences endpoints on the BookPlayer backend (iOS parity: `PreferencesAPI`). A preference
 * is a key + a JSON-object value; the library sort feature stores one entry per level
 * (`library_sort:default` / `library_sort:<uuid>`).
 *
 * - GET  /v1/user/preferences?prefix=…      → the user's preferences (optionally filtered by prefix)
 * - PATCH /v1/user/preferences  {entries:[{key,value}]}  → upsert a batch
 * - DELETE /v1/user/preferences {keys:[…]}  → remove by key
 */
@JvmSuppressWildcards
interface PreferencesApi {
    @GET("/v1/user/preferences")
    suspend fun getPreferences(@Query("prefix") prefix: String? = null): Response<PreferencesResponse>

    @PATCH("/v1/user/preferences")
    suspend fun setPreferences(@Body body: Map<String, Any?>): Response<Unit>

    @HTTP(method = "DELETE", path = "/v1/user/preferences", hasBody = true)
    suspend fun deletePreferences(@Body body: Map<String, Any?>): Response<Unit>
}

/** GET response: a list of entries, each a key and its JSON-object value. */
data class PreferencesResponse(
    val entries: List<PreferenceEntryDto>? = null
)

data class PreferenceEntryDto(
    val key: String,
    val value: Map<String, Any?>? = null
)
