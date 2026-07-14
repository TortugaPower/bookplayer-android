package com.tortugapower.audiobookplayer.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * User-preferences endpoints on the BookPlayer backend. This is the transport for the preference
 * sync channel (library sort rules, and future display prefs) — deliberately separate from the
 * per-item library endpoints in [LibraryApi], because preferences are user-scoped key/value pairs,
 * not library items, and never ride the item-sync queue.
 */
@JvmSuppressWildcards
interface PreferencesApi {
    /** Fetch every stored preference for the signed-in user. */
    @GET("/v1/user/preferences")
    suspend fun getPreferences(): Response<PreferencesResponse>

    /** Upsert a batch of changed preferences. Only the dirty keys are sent. */
    @POST("/v1/user/preferences")
    suspend fun putPreferences(@Body body: Map<String, Any?>): Response<Unit>
}

/**
 * The server's preference map. Values are raw JSON scalars (String / Boolean / Number), so the
 * client normalizes them to canonical strings and validates per preference family before applying —
 * booleans in particular may arrive as 0/1.
 */
data class PreferencesResponse(
    val preferences: Map<String, Any?>? = null
)
